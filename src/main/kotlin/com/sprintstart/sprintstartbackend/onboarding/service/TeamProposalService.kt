package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.assistant.external.BuddyActionResponse
import com.sprintstart.sprintstartbackend.onboarding.assistant.external.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.assistant.external.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.onboarding.assistant.model.entity.BuddyActionProposal
import com.sprintstart.sprintstartbackend.onboarding.assistant.service.ActionProposalStore
import com.sprintstart.sprintstartbackend.shared.ids.ActionProposalId
import com.sprintstart.sprintstartbackend.shared.ids.AuthId
import com.sprintstart.sprintstartbackend.shared.ids.ProjectId
import com.sprintstart.sprintstartbackend.shared.ids.UserId
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The changes the team-mode buddy offers a manager, run only when they confirm them.
 *
 * Proposing changes nothing but the stored proposal. Confirming runs, in order:
 *
 * 1. **Owner.** Only the manager the proposal was made for can decide it; anybody else gets a 404.
 * 2. **Still open**, and claimed exactly once, through [ActionProposalStore.claim].
 * 3. **Still the manager**, through [UserApi.canManageProject].
 * 4. **Still valid**, through [TeamActionHandler.recheck].
 * 5. **Perform**, then record `CONFIRMED` or `FAILED`, even if the action throws.
 */
@Service
class TeamProposalService(
    private val proposals: ActionProposalStore,
    private val userApi: UserApi,
    private val handlersProvider: ObjectProvider<TeamActionHandler>,
) {
    private val handlers: Map<String, TeamActionHandler> by lazy {
        handlersProvider.orderedStream().toList().associateBy { it.spec.name }
    }

    fun actionAreas(): Set<TeamArea> = handlers.values.map { it.area }.toSet()

    fun actionSpecs(openedAreas: Set<TeamArea>): List<BuddyToolSpecDto> =
        handlers.values.filter { it.area in openedAreas }.map { it.spec }

    fun isAction(toolName: String): Boolean = toolName in handlers

    /**
     * Turns an action call into a stored proposal, or into the reason it cannot be offered.
     *
     * @return What to tell the model, and the stored proposal when there is one to show.
     */
    fun propose(call: BuddyToolCallDto, context: TeamToolContext): ProposeOutcome {
        val handler = handlers[call.name] ?: return ProposeOutcome("Unknown action: ${call.name}.", null)
        return when (val draft = handler.draft(call, context)) {
            is TeamActionDraft.Refused -> ProposeOutcome(draft.reason, null)
            is TeamActionDraft.Proposed -> ProposeOutcome(
                toolResult = "Proposed to the manager: “${draft.label}”. They see a confirm button with this " +
                    "preview, and nothing changes unless they confirm it. Offer it — never say it is done.",
                proposal = proposals.open(
                    userId = UserId(context.userId),
                    projectId = ProjectId(context.projectId),
                    offer = ActionProposalStore.Offer(
                        action = handler.spec.name,
                        risk = handler.risk,
                        params = draft.params,
                        label = draft.label,
                        preview = draft.preview,
                    ),
                ),
            )
        }
    }

    /**
     * Runs a proposal the manager confirmed, if it may still run.
     *
     * @return The line to show; a refusal or a handled failure is `ok = false`, not an error.
     * @throws ResponseStatusException 404 when the caller does not exist or the proposal is not theirs.
     */
    suspend fun confirm(authId: String, proposalId: UUID): BuddyActionResponse {
        val proposal = proposals.owned(resolveUserId(AuthId(authId)), ActionProposalId(proposalId))
        proposals.claim(proposal)?.let { return it }
        return proposals.settle(ActionProposalId(proposal.id)) { attempt(proposal, authId) }
    }

    /**
     * Declines a proposal. Nothing changes but the proposal itself.
     *
     * @throws ResponseStatusException 404 when the caller does not exist or the proposal is not theirs.
     */
    fun dismiss(authId: String, proposalId: UUID): BuddyActionResponse =
        proposals.dismiss(proposals.owned(resolveUserId(AuthId(authId)), ActionProposalId(proposalId)))

    private suspend fun attempt(proposal: BuddyActionProposal, authId: String): BuddyActionResponse {
        if (!userApi.canManageProject(authId, proposal.projectId)) {
            return BuddyActionResponse(ok = false, message = "You no longer manage this project, so this was not done.")
        }
        val handler = handlers[proposal.action]
            ?: return BuddyActionResponse(
                ok = false,
                message = "This action is no longer available, so nothing was done.",
            )
        val params = proposals.params(proposal)
        val context = TeamToolContext(userId = proposal.userId, authId = authId, projectId = proposal.projectId)
        handler.recheck(params, context)?.let { return BuddyActionResponse(ok = false, message = it) }
        return BuddyActionResponse(ok = true, message = handler.perform(params, context))
    }

    private fun resolveUserId(authId: AuthId): UserId =
        userApi
            .getUserIdByAuthId(authId.value)
            .map(::UserId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: ${authId.value}") }

    data class ProposeOutcome(
        val toolResult: String,
        val proposal: BuddyActionProposal?,
    )
}
