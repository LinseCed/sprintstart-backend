package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGoal
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GoalView
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGoalRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * A hire claiming, reading and dropping the contribution their path aims at.
 *
 * The hire chooses, not a PM and not a scoring function: `GET /me/matches` already ranks the
 * approved starter-work pool by fit, and this turns one of those into a commitment. A PM still
 * controls *which* tasks exist -- only an APPROVED proposal can be claimed -- so this is a choice
 * within a curated set, not an open field.
 *
 * "No goal yet" is a real, nameable state rather than an error or a silent fallback to the whole
 * graph: the path is simply the project's baseline until a hire picks a destination, and the
 * payload says so.
 */
@Service
class UserGoalService(
    private val userGoalRepository: UserGoalRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val competencyRepository: CompetencyRepository,
    private val userApi: UserApi,
) {
    /**
     * Claims an approved starter-work task as this hire's goal for [projectId], replacing any
     * goal they had claimed there before.
     *
     * @throws ResponseStatusException 404 if the proposal or its CONTRIBUTION node doesn't exist;
     * 409 if the proposal has not been approved (a PROPOSED or REJECTED task is not something a
     * hire may commit to).
     */
    @Transactional
    fun claimForMe(authId: String, projectId: UUID, proposalId: UUID): GoalView {
        val userId = resolveUserId(authId)
        val proposal = starterWorkTaskProposalRepository.findById(proposalId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No starter-work task found with id: $proposalId")
        }
        if (proposal.status != ProposalStatus.APPROVED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Starter-work task $proposalId is ${proposal.status}; only an approved task can be claimed as a goal",
            )
        }

        // Resolved through the same deterministic key derivation approval used, never by
        // matching on label or URL -- a PM can rename a node (#50), and a goal that stopped
        // resolving because somebody fixed a typo would be a very confusing bug.
        //
        // Approval is what mints the node, so its absence means the graph and the proposal table
        // disagree: claim a goal that isn't a node and the path would silently aim at nothing.
        val contributionKey = StarterWorkTaskProposalService.contributionKeyFor(proposal.sourceId)
        val contribution = competencyRepository.findByKey(contributionKey)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Starter-work task $proposalId has no contribution node in the graph",
            )

        val existing = userGoalRepository.findByUserIdAndProjectId(userId, projectId)
        val goal = existing?.apply {
            competencyKey = contribution.key
            sourceProposalId = proposal.id
            claimedAt = Instant.now()
        } ?: UserGoal(
            userId = userId,
            projectId = projectId,
            competencyKey = contribution.key,
            sourceProposalId = proposal.id,
        )
        userGoalRepository.save(goal)

        return GoalView(
            competencyKey = contribution.key,
            label = contribution.label,
            summary = proposal.summary,
            sourceUrl = proposal.sourceUrl,
            sourceProposalId = proposal.id,
        )
    }

    /** Drops this hire's goal for [projectId]; the path falls back to the project's baseline. */
    @Transactional
    fun clearForMe(authId: String, projectId: UUID) {
        userGoalRepository.deleteByUserIdAndProjectId(resolveUserId(authId), projectId)
    }

    /**
     * This hire's claimed goal for [projectId], or `null` when they haven't picked one.
     *
     * Resolved against the live graph so a goal whose node a PM has since removed reads as "no
     * goal" rather than pointing at something that is no longer there.
     */
    @Transactional(readOnly = true)
    fun findForUser(userId: UUID, projectId: UUID, visibleKeys: Set<String>): UserGoal? =
        userGoalRepository
            .findByUserIdAndProjectId(userId, projectId)
            ?.takeIf { it.competencyKey in visibleKeys }

    private fun resolveUserId(authId: String): UUID =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }
}
