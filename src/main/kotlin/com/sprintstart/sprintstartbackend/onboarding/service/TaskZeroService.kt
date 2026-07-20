package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.TaskZeroAssignment
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.MyTaskZeroResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkTaskProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.TaskZeroAssignmentRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Task 0: the trivial first task that proves the branch → PR → review → merge loop.
 *
 * Assignment is automatic once environment readiness is settled — a hire's first day should never
 * end with "pick something" — and undoable. The task comes from the same mined-and-approved pool as
 * every other, flagged as Task-0-suitable by a PM, because a task nobody wanted is not a
 * contribution and hires can tell.
 *
 * Completing it proves the loop and nothing else: there is **no** `UserCompetencyState` write
 * anywhere here, and this service does not depend on the ledger. "Loop proven" is derived — the
 * hire has merged a pull request — never stored as earned competence.
 */
@Service
class TaskZeroService(
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val taskZeroAssignmentRepository: TaskZeroAssignmentRepository,
    private val environmentReadinessService: EnvironmentReadinessService,
    private val projectMembershipApi: ProjectMembershipApi,
    private val artifactIngestionApi: ArtifactIngestionApi,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Sets whether an approved starter-work task is a Task 0 candidate.
     *
     * @throws ResponseStatusException 404 when no such proposal exists; 409 when it is not APPROVED
     * (Task-0 candidacy is a property of the approved pool, not of a proposal still under review).
     */
    @Transactional
    fun setEligibility(proposalId: UUID, eligible: Boolean): StarterWorkTaskProposalResponse {
        val proposal = starterWorkTaskProposalRepository.findById(proposalId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No starter-work task found with id $proposalId")
        }
        if (proposal.status != ProposalStatus.APPROVED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only an approved task can be flagged for Task 0",
            )
        }
        proposal.taskZeroEligible = eligible
        return starterWorkTaskProposalRepository.save(proposal).toResponse()
    }

    /**
     * The hire's Task 0, assigning one if their environment is ready and none is assigned yet.
     *
     * The assignment happens lazily here rather than on a background trigger, so it covers both
     * reported and *derived* readiness (a hire who just opened their first pull request is ready
     * without ever running the command). Not-ready, and ready-but-nothing-eligible, are both
     * ordinary returned states.
     *
     * @throws ResponseStatusException 404 when the hire is not a member of the project.
     */
    @Transactional
    fun getForHire(hireId: UUID, projectId: UUID): MyTaskZeroResponse {
        val member = requireMember(hireId, projectId)
        val ready = environmentReadinessService.readyAtFor(member, projectId) != null
        val loopProven = hasMergedPullRequest(member, projectId)

        if (!ready) {
            return MyTaskZeroResponse(
                ready = false,
                task = null,
                assignedAt = null,
                noneAvailable = false,
                loopProven = loopProven,
            )
        }

        taskZeroAssignmentRepository.findByHireIdAndProjectId(hireId, projectId)?.let { existing ->
            return existing.toResponse(loopProven)
        }

        val candidate = nextEligibleTask()
            ?: return MyTaskZeroResponse(
                ready = true,
                task = null,
                assignedAt = null,
                noneAvailable = true,
                loopProven = loopProven,
            )

        val saved = taskZeroAssignmentRepository.save(
            TaskZeroAssignment(
                hireId = hireId,
                projectId = projectId,
                proposalId = candidate.id,
                assignedAt = clock.instant(),
            ),
        )
        return MyTaskZeroResponse(
            ready = true,
            task = candidate.toResponse(),
            assignedAt = saved.assignedAt,
            noneAvailable = false,
            loopProven = loopProven,
        )
    }

    /**
     * Undoes a hire's Task 0 assignment, freeing the task for someone else. A no-op when nothing is
     * assigned. Does not touch readiness or any earned progress — there is none to un-earn.
     */
    @Transactional
    fun unassign(hireId: UUID, projectId: UUID) {
        taskZeroAssignmentRepository.deleteByHireIdAndProjectId(hireId, projectId)
    }

    /** When Task 0 was assigned, for the metrics timeline. Read-only, never assigns as a side effect. */
    @Transactional(readOnly = true)
    fun assignedAtFor(hireId: UUID, projectId: UUID): Instant? =
        taskZeroAssignmentRepository.findByHireIdAndProjectId(hireId, projectId)?.assignedAt

    private fun nextEligibleTask(): StarterWorkTaskProposal? {
        val taken = taskZeroAssignmentRepository.findAllAssignedProposalIds().toSet()
        return starterWorkTaskProposalRepository
            .findAllByStatusAndTaskZeroEligibleTrue(ProposalStatus.APPROVED)
            .filter { it.id !in taken }
            .minByOrNull { it.createdAt }
    }

    private fun hasMergedPullRequest(member: ProjectMember, projectId: UUID): Boolean {
        val login = member.githubLogin
        if (login.isNullOrBlank()) return false
        return artifactIngestionApi.getAuthoredPullRequests(projectId, login).any { it.mergedAt != null }
    }

    private fun requireMember(hireId: UUID, projectId: UUID): ProjectMember =
        projectMembershipApi.getProjectMembers(projectId).firstOrNull { it.userId == hireId }
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "User $hireId is not a member of project $projectId",
            )

    private fun TaskZeroAssignment.toResponse(loopProven: Boolean): MyTaskZeroResponse {
        val proposal = starterWorkTaskProposalRepository.findById(proposalId).orElse(null)
        return MyTaskZeroResponse(
            ready = true,
            task = proposal?.toResponse(),
            assignedAt = assignedAt,
            noneAvailable = false,
            loopProven = loopProven,
        )
    }
}
