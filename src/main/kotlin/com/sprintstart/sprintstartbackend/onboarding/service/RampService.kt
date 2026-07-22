package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.ingestion.external.AuthoredPullRequest
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.RampStage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.AutonomyMilestone
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.ramp.AutonomyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.ramp.MyRampResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.AutonomyMilestoneRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.KnowledgeRequestRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.TaskZeroAssignmentRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGoalRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

/**
 * The ramp of real tasks, and the exit from onboarding.
 *
 * Three decisions shape this service.
 *
 * **The ledger is written by merged pull requests, not by chat.** A merged change credits the
 * competencies of the task it was claimed against, at that competency's own target level, with
 * [CompetencySource.VERIFIED] and the same monotonic rule as every other ledger writer — a merge
 * never lowers what somebody already showed. Chat placement stays a weak prior that a merge
 * outranks, never the other way round.
 *
 * **Task 0 credits nothing, by construction rather than by convention.** Credit is derived from the
 * *claimed goal*, and Task 0 is an assignment, not a goal — so there is no code path that could
 * credit it. That is deliberate: its job is confidence and mechanics, and a ledger entry for
 * "opened a pull request once" would be a lie about competence.
 *
 * **Autonomy is an event, not a state.** The exit condition is a task completed with no help from
 * a person (an escalation to the PM, the surviving human channel) and no review rework, which is
 * the honest operational definition of "can be left alone here" — not "all nodes mastered". The
 * moment is recorded once ([AutonomyMilestone]) so it can be announced and dated; recomputing it
 * would only ever yield a boolean, and a boolean cannot be announced.
 */
@Service
class RampService(
    private val taskZeroAssignmentRepository: TaskZeroAssignmentRepository,
    private val userGoalRepository: UserGoalRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val competencyRepository: CompetencyRepository,
    private val autonomyMilestoneRepository: AutonomyMilestoneRepository,
    private val knowledgeRequestRepository: KnowledgeRequestRepository,
    private val projectMembershipApi: ProjectMembershipApi,
    private val artifactIngestionApi: ArtifactIngestionApi,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * A hire's ramp on one project, crediting any merged work not yet credited.
     *
     * Crediting happens lazily on read for the same reason Task 0 assigns lazily: it covers work
     * that merged while nobody was looking, with no scheduler and no backfill. It is idempotent —
     * the ledger write is a monotonic find-or-create, so reading twice credits once.
     *
     * @throws ResponseStatusException 404 when the hire is not a member of the project.
     */
    @Transactional
    fun getForHire(hireId: UUID, projectId: UUID): MyRampResponse {
        val member = requireMember(hireId, projectId)
        val pullRequests = authoredPullRequests(member, projectId)
        val merged = pullRequests.filter { it.mergedAt != null }

        val credited = creditMergedWork(hireId, projectId, merged)
        val autonomy = evaluateAutonomy(hireId, projectId, merged)
        val currentTask = currentTask(hireId, projectId)

        return MyRampResponse(
            stage = stageOf(merged.size, autonomy.reached),
            currentTask = currentTask?.toResponse(),
            unlockedBy = unlockedBy(merged.size, autonomy.reached),
            mergedCount = merged.size,
            creditedCompetencyKeys = credited,
            autonomy = autonomy,
        )
    }

    /** When a hire reached autonomy on a project, for the PM readout. Never writes. */
    @Transactional(readOnly = true)
    fun autonomyReachedAtFor(hireId: UUID, projectId: UUID) =
        autonomyMilestoneRepository.findByHireIdAndProjectId(hireId, projectId)?.reachedAt

    /**
     * The stage a hire is on.
     *
     * Counted in *merged changes*, because that is the only unit of progress the ramp recognises.
     * Task 0 is where somebody sits before they have merged anything — including a hire who has one
     * open pull request, since an unmerged change has not proven the loop.
     */
    private fun stageOf(mergedCount: Int, autonomous: Boolean): RampStage = when {
        autonomous -> RampStage.AUTONOMOUS
        mergedCount == 0 -> RampStage.TASK_ZERO
        mergedCount == 1 -> RampStage.TASK_ONE
        else -> RampStage.TASK_TWO_PLUS
    }

    private fun unlockedBy(mergedCount: Int, autonomous: Boolean): String = when {
        autonomous -> "You shipped a change with no help and no rework"
        mergedCount == 0 -> "You haven't merged anything here yet — that's the whole first step"
        mergedCount == 1 -> "You merged your first change here"
        else -> "You've merged $mergedCount changes here"
    }

    /**
     * Writes ledger credit for competencies proven by merged work.
     *
     * **What a merge is attributed to.** Ingestion records who authored a pull request but not
     * which task it was for, so a merge is attributed to the goal the hire had *claimed at the
     * time* — a pull request that merged after the claim. That is an approximation and worth
     * naming: without a task↔PR link there is no exact answer, and the claimed goal is the best
     * evidence available. It cannot over-credit an unrelated person's work, because authorship is
     * already enforced (the pull requests come from the hire's own declared GitHub login).
     *
     * @return The competency keys credited, for the hire to see what their work counted for.
     */
    private fun creditMergedWork(
        hireId: UUID,
        projectId: UUID,
        merged: List<AuthoredPullRequest>,
    ): List<String> {
        val goal = userGoalRepository.findByUserIdAndProjectId(hireId, projectId) ?: return emptyList()
        val proposal = goal.sourceProposalId
            ?.let { starterWorkTaskProposalRepository.findById(it).orElse(null) }
            ?: return emptyList()

        val qualifying = merged.any { pr -> pr.mergedAt?.isAfter(goal.claimedAt) == true }
        if (!qualifying) return emptyList()

        val competencies = competencyRepository.findAllByKeyIn(proposal.competencyKeys).associateBy { it.key }
        return proposal.competencyKeys.mapNotNull { key ->
            val competency = competencies[key] ?: return@mapNotNull null
            creditCompetency(hireId, competency)
            key
        }
    }

    /**
     * Monotonic find-or-create, mirroring `VerificationService`'s ledger write.
     *
     * Credit lands at the competency's **own target level**: a merge is evidence of meeting the
     * bar the project set for that competency, not of some level the merge itself implies.
     */
    private fun creditCompetency(hireId: UUID, competency: Competency) {
        val existing = userCompetencyStateRepository.findByUserIdAndCompetencyKey(hireId, competency.key)
        if (existing != null) {
            // Never un-earns: a merge cannot lower a level already shown, only raise it and
            // upgrade the source to VERIFIED.
            existing.level = maxOf(existing.level, competency.targetLevel)
            existing.source = CompetencySource.VERIFIED
            existing.updatedAt = clock.instant()
        } else {
            userCompetencyStateRepository.save(
                UserCompetencyState(
                    userId = hireId,
                    competencyKey = competency.key,
                    level = competency.targetLevel,
                    source = CompetencySource.VERIFIED,
                ),
            )
        }
    }

    /**
     * Whether a hire has shown they can work here unsupervised, and what is missing if not.
     *
     * The condition is evaluated against the **most recent merged pull request**, because autonomy
     * is a claim about how somebody works now. Both halves must hold on that one task: no review
     * asked for changes, and no person was pulled in between opening it and merging it.
     */
    private fun evaluateAutonomy(
        hireId: UUID,
        projectId: UUID,
        merged: List<AuthoredPullRequest>,
    ): AutonomyResponse {
        autonomyMilestoneRepository.findByHireIdAndProjectId(hireId, projectId)?.let {
            return AutonomyResponse(
                reached = true,
                reachedAt = it.reachedAt,
                provenByArtifactId = it.provenByArtifactId,
                blockers = emptyList(),
            )
        }

        val latest = merged.maxByOrNull { it.mergedAt!! }
            ?: return AutonomyResponse(
                reached = false,
                reachedAt = null,
                provenByArtifactId = null,
                blockers = listOf("No merged change here yet"),
            )

        val blockers = mutableListOf<String>()
        if (latest.changesRequestedCount > 0) {
            blockers += "Your last merged change was sent back for rework"
        }
        if (neededHelp(hireId, projectId, latest)) {
            blockers += "You pulled in a person while you were on your last change"
        }
        if (blockers.isNotEmpty()) {
            return AutonomyResponse(
                reached = false,
                reachedAt = null,
                provenByArtifactId = null,
                blockers = blockers,
            )
        }

        // Recorded at the merge itself, not at the moment we noticed -- the date has to be the
        // one that actually happened, or the announcement is about our polling.
        val milestone = autonomyMilestoneRepository.save(
            AutonomyMilestone(
                hireId = hireId,
                projectId = projectId,
                reachedAt = latest.mergedAt!!,
                provenByArtifactId = latest.artifactId,
            ),
        )
        return AutonomyResponse(
            reached = true,
            reachedAt = milestone.reachedAt,
            provenByArtifactId = milestone.provenByArtifactId,
            blockers = emptyList(),
        )
    }

    /**
     * Whether the hire needed a person while this change was open.
     *
     * Scoped to the change's own window rather than "ever": a hire who needed help in week one and
     * shipped week four's change alone has demonstrated exactly what the exit condition asks about.
     * "Help" is what the surviving human channel records: the assigned-buddy loop is retired, so
     * this is now an escalation to the PM (flag-to-PM) during the window — the only reaching out
     * a hire can still do.
     */
    private fun neededHelp(hireId: UUID, projectId: UUID, pullRequest: AuthoredPullRequest): Boolean {
        val opened = pullRequest.openedAt ?: return false
        val merged = pullRequest.mergedAt ?: return false
        return knowledgeRequestRepository
            .findAllByHireIdAndProjectId(hireId, projectId)
            .any { !it.createdAt.isBefore(opened) && !it.createdAt.isAfter(merged) }
    }

    /**
     * The task a hire is on: their claimed goal, or their assigned Task 0 before they have one.
     *
     * Read-only — unlike `TaskZeroService.getForHire`, this never assigns. Seeing where you are on
     * the ramp must not be what hands you your first task.
     */
    private fun currentTask(hireId: UUID, projectId: UUID) =
        userGoalRepository
            .findByUserIdAndProjectId(hireId, projectId)
            ?.sourceProposalId
            ?.let { starterWorkTaskProposalRepository.findById(it).orElse(null) }
            ?: taskZeroAssignmentRepository
                .findByHireIdAndProjectId(hireId, projectId)
                ?.let { starterWorkTaskProposalRepository.findById(it.proposalId).orElse(null) }

    private fun authoredPullRequests(member: ProjectMember, projectId: UUID): List<AuthoredPullRequest> {
        val login = member.githubLogin
        if (login.isNullOrBlank()) return emptyList()
        return artifactIngestionApi.getAuthoredPullRequests(projectId, login)
    }

    private fun requireMember(hireId: UUID, projectId: UUID): ProjectMember =
        projectMembershipApi.getProjectMembers(projectId).firstOrNull { it.userId == hireId }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User $hireId is not a member of project $projectId",
            )
}
