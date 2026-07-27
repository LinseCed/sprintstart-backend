package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.RungState
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.SetupReadinessResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.SetupRungResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * "Is this project ready to onboard someone?" answered as a ladder of the two setup stages the
 * onboarding module owns: an approved skill map, and a stocked pool of starter tasks. (The corpus
 * stage lives in the ingestion module; see [SetupReadinessResponse].)
 *
 * Two rungs have gone since it was written, both because the work behind them stopped existing. A
 * buddy for every hire went with the human-buddy loop — the AI buddy needs no assignment. A chosen
 * baseline went when the path started deriving its targets from the hire's claimed goal: the rung
 * was asking a PM to make a selection nothing read, which is a worse failure than a missing rung
 * because it looks like progress.
 *
 * Every number is composed on read from the same rows each stage's own page reads, so this can never
 * disagree with those pages -- and the bug that motivated it (proposals generated but never approved,
 * so a page read "empty") shows up here as an explicit "waiting for your review" instead of a silent
 * contradiction between two surfaces.
 */
@Service
class SetupReadinessService(
    private val competencyGraphAuthoringService: CompetencyGraphAuthoringService,
    private val competencyProposalRepository: CompetencyProposalRepository,
    private val competencyEdgeProposalRepository: CompetencyEdgeProposalRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val projectMembershipApi: ProjectMembershipApi,
    private val trackService: TrackService,
) {
    @Transactional(readOnly = true)
    fun getReadiness(projectId: UUID): SetupReadinessResponse {
        val approvedCompetencies = competencyGraphAuthoringService.getGraph().competencies.size
        val rungs = listOf(
            skillMapRung(approvedCompetencies),
            starterTasksRung(),
        )
        return SetupReadinessResponse(
            projectId = projectId,
            rungs = rungs,
            ready = rungs.all { it.state == RungState.OK },
        )
    }

    private fun skillMapRung(approvedCompetencies: Int): SetupRungResponse {
        val pendingNodes = competencyProposalRepository.findAllByStatus(ProposalStatus.PROPOSED).size
        val pendingEdges = competencyEdgeProposalRepository.findAllByStatus(ProposalStatus.PROPOSED).size
        val pending = pendingNodes + pendingEdges
        return when {
            pending > 0 -> rung(
                SKILL_MAP,
                RungState.WARN,
                approvedCompetencies,
                "$pendingNodes ${competencyWord(pendingNodes)} and $pendingEdges " +
                    "${edgeWord(pendingEdges)} are waiting for your review.",
            )
            approvedCompetencies == 0 -> rung(
                SKILL_MAP,
                RungState.WARN,
                0,
                "No competencies yet. Generate a skill map from the ingested corpus.",
            )
            else -> rung(
                SKILL_MAP,
                RungState.OK,
                approvedCompetencies,
                "$approvedCompetencies ${competencyWord(approvedCompetencies)} approved.",
            )
        }
    }

    /**
     * Whether there is starter work to claim — and whether it covers every track in use.
     *
     * A count alone hid a real failure: a project can have plenty of approved tasks and still have
     * none a delivery lead could do, because every one of them was a mined GitHub issue. A hire in
     * that position sees an empty suggestion list on a project the ladder called ready.
     *
     * Unscoped tasks count toward every track: null means "suits any role", so they are coverage
     * for everybody rather than for nobody.
     */
    private fun starterTasksRung(): SetupRungResponse {
        val approvedTasks = starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED)
        val approved = approvedTasks.size
        val pending = starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.PROPOSED).size
        val uncovered = uncoveredTracks(approvedTasks)
        return when {
            approved > 0 && uncovered.isNotEmpty() -> rung(
                STARTER_TASKS,
                RungState.WARN,
                approved,
                "$approved starter ${taskWord(approved)} ready to claim, but nothing a hire on " +
                    "${uncovered.joinToString(" or ")} could pick up.",
            )
            approved > 0 -> rung(
                STARTER_TASKS,
                RungState.OK,
                approved,
                "$approved starter ${taskWord(approved)} ready to claim.",
            )
            pending > 0 -> rung(
                STARTER_TASKS,
                RungState.WARN,
                0,
                "$pending mined ${taskWord(pending)} waiting for your review.",
            )
            else -> rung(
                STARTER_TASKS,
                RungState.WARN,
                0,
                "No starter tasks yet. Mine well-scoped first tasks from the corpus.",
            )
        }
    }

    /**
     * Tracks that some role points at but no claimable task serves.
     *
     * Read from the tracks roles actually use rather than every track that exists: warning about a
     * track nobody is on is noise a PM cannot act on.
     */
    private fun uncoveredTracks(approvedTasks: List<StarterWorkTaskProposal>): List<String> {
        if (approvedTasks.any { it.onboardingTrackKey == null }) {
            return emptyList()
        }
        val covered = approvedTasks.mapNotNull { it.onboardingTrackKey }.toSet()
        return trackService
            .tracksInUse()
            .filter { it.key !in covered }
            .map { it.label }
    }

    private fun rung(key: String, state: RungState, count: Int, detail: String) =
        SetupRungResponse(key = key, state = state, count = count, detail = detail)

    private fun competencyWord(n: Int) = if (n == 1) "competency" else "competencies"

    private fun edgeWord(n: Int) = if (n == 1) "edge" else "edges"

    private fun taskWord(n: Int) = if (n == 1) "task" else "tasks"

    private companion object {
        const val SKILL_MAP = "skill-map"
        const val STARTER_TASKS = "starter-tasks"
    }
}
