package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.RungState
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.SetupReadinessResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.SetupRungResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingBuddyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * "Is this project ready to onboard someone?" answered as a ladder of the four setup stages the
 * onboarding module owns: an approved skill map, a chosen baseline, a stocked pool of starter tasks,
 * and a buddy for every hire. (The corpus stage lives in the ingestion module; see
 * [SetupReadinessResponse].)
 *
 * Every number is composed on read from the same rows each stage's own page reads, so this can never
 * disagree with those pages -- and the bug that motivated it (proposals generated but never approved,
 * so the baseline page read "empty") shows up here as an explicit "waiting for your review" instead
 * of a silent contradiction between two surfaces.
 */
@Service
class SetupReadinessService(
    private val competencyGraphAuthoringService: CompetencyGraphAuthoringService,
    private val competencyProposalRepository: CompetencyProposalRepository,
    private val competencyEdgeProposalRepository: CompetencyEdgeProposalRepository,
    private val blueprintRepository: BlueprintRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val onboardingBuddyRepository: OnboardingBuddyRepository,
    private val projectMembershipApi: ProjectMembershipApi,
) {
    @Transactional(readOnly = true)
    fun getReadiness(projectId: UUID): SetupReadinessResponse {
        val approvedCompetencies = competencyGraphAuthoringService.getGraph().competencies.size
        val rungs = listOf(
            skillMapRung(approvedCompetencies),
            baselineRung(projectId, approvedCompetencies),
            starterTasksRung(),
            humanLoopRung(projectId),
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

    private fun baselineRung(projectId: UUID, approvedCompetencies: Int): SetupRungResponse {
        // A baseline is a selection *from* the approved competencies, so it cannot be chosen before
        // any exist -- that is the one genuine ordering constraint in the ladder.
        if (approvedCompetencies == 0) {
            return rung(
                BASELINE,
                RungState.BLOCKED,
                0,
                "Approve competencies first, then mark which ones matter on this project.",
            )
        }
        val baselineCount = blueprintRepository
            .findAllByStatus(BlueprintStatus.ACTIVE)
            .filter { it.projectId == projectId }
            .sumOf { blueprint -> blueprint.competencies.count { it.status == ProposalStatus.APPROVED } }
        return when (baselineCount) {
            0 -> rung(
                BASELINE,
                RungState.WARN,
                0,
                "No baseline yet. Choose the competencies expected of a hire on this project.",
            )
            else -> rung(
                BASELINE,
                RungState.OK,
                baselineCount,
                "$baselineCount ${competencyWord(baselineCount)} expected on this project.",
            )
        }
    }

    private fun starterTasksRung(): SetupRungResponse {
        val approved = starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED).size
        val pending = starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.PROPOSED).size
        return when {
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

    private fun humanLoopRung(projectId: UUID): SetupRungResponse {
        val hires = projectMembershipApi.getProjectMembers(projectId)
        val hiresWithBuddy = onboardingBuddyRepository
            .findAllByProjectId(projectId)
            .map { it.hireId }
            .toSet()
        val covered = hires.count { it.userId in hiresWithBuddy }
        val total = hires.size
        return when {
            total == 0 -> rung(
                HUMAN_LOOP,
                RungState.WARN,
                0,
                "No hires on this project yet.",
            )
            covered == total -> rung(
                HUMAN_LOOP,
                RungState.OK,
                covered,
                "Every hire has a buddy ($covered of $total).",
            )
            else -> rung(
                HUMAN_LOOP,
                RungState.WARN,
                covered,
                "${total - covered} ${hireWord(total - covered)} still ${needsWord(total - covered)} " +
                    "a buddy ($covered of $total).",
            )
        }
    }

    private fun rung(key: String, state: RungState, count: Int, detail: String) =
        SetupRungResponse(key = key, state = state, count = count, detail = detail)

    private fun competencyWord(n: Int) = if (n == 1) "competency" else "competencies"

    private fun edgeWord(n: Int) = if (n == 1) "edge" else "edges"

    private fun taskWord(n: Int) = if (n == 1) "task" else "tasks"

    private fun hireWord(n: Int) = if (n == 1) "hire" else "hires"

    private fun needsWord(n: Int) = if (n == 1) "needs" else "need"

    private companion object {
        const val SKILL_MAP = "skill-map"
        const val BASELINE = "baseline"
        const val STARTER_TASKS = "starter-tasks"
        const val HUMAN_LOOP = "human-loop"
    }
}
