package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.BuddyActionType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.QuestionStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepOrigin
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepType
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.onboarding.model.request.buddy.BuddyActionRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.skip.CreateOnboardingSkipRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.step.CreateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.task.UpdateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.buddy.BuddyActionResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.GetOnboardingPhaseForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.shared.ids.UserId
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The actions that let a conversation move the hire along their onboarding path.
 *
 * A component of its own rather than more branches in [BuddyActionService], for the reason
 * [BuddyBoardTools] is one: they share a subject that the rest of the catalog does not touch, and
 * they come as a set. [BuddyActionService] still owns the propose/confirm contract — it routes to
 * this and emits what comes back — so there is exactly one place where an action becomes a button.
 *
 * ### The line these do not cross
 *
 * They write to **the hire's own copy of the path**, never to the blueprint it was copied from. The
 * curriculum is the PM's: a mentor that could edit it is a mentor whose team stops trusting it.
 * Everything here is undoable on the hire's own onboarding page, which is what makes proposing any
 * of it reasonable.
 *
 * ### Why they sit outside the project gate
 *
 * An onboarding path belongs to a *person*. It is generated from one project's blueprint, but the
 * path itself is not project-scoped — so gating these would refuse a hire onboarding on two
 * projects, who still has exactly one path, on the page they are looking at.
 */
@Component
@Suppress("TooManyFunctions")
class BuddyPathActions(
    private val buddyPathTools: BuddyPathTools,
    private val onboardingStepService: OnboardingStepService,
    private val onboardingStepPlacementService: OnboardingStepPlacementService,
    private val onboardingTaskService: OnboardingTaskService,
    private val questions: BuddyQuestionActions,
    private val onboardingSkipService: OnboardingSkipService,
    private val userApi: UserApi,
) {
    /**
     * The path actions, or none at all for a hire without a path.
     *
     * Gated on the path's existence and on nothing else — never on how far along it they are. A
     * mentor that may only propose something once some further condition holds is one whose refusals
     * the hire has to learn; each proposal checks its own preconditions, where the reason can be a
     * sentence. Asked of [BuddyPathTools], so the read tool and these actions can never disagree
     * about whether there is a path.
     */
    fun specs(userId: UUID): List<BuddyToolSpecDto> =
        if (!buddyPathTools.hasPath(userId)) {
            emptyList()
        } else {
            listOf(
                COMPLETE_STEP_SPEC,
                COMPLETE_TASK_SPEC,
                BuddyQuestionActions.SPEC,
                ADD_PATH_STEP_SPEC,
                REQUEST_SKIP_SPEC,
            )
        }

    /** Whether [type] is one of this component's actions. */
    fun handles(type: BuddyActionType): Boolean =
        type == BuddyActionType.COMPLETE_STEP ||
            type == BuddyActionType.COMPLETE_TASK ||
            type == BuddyActionType.ANSWER_QUESTION ||
            type == BuddyActionType.ADD_PATH_STEP ||
            type == BuddyActionType.REQUEST_SKIP

    /**
     * Offers one of them, checked against the hire's own path before the hire sees a button.
     *
     * Every precondition is resolved here rather than at confirm time, for the reason the assessment
     * proposal gives: a step that is already finished, a locked question, an answer that matches no
     * option — discovered now, each is a correction the mentor can act on mid-sentence; discovered
     * after the click, each is a hire pressing a button and being told no.
     *
     * The refusals are addressed to the mentor, not to the hire, and each says what to do instead. A
     * tool result that only says "no" is one the model relays as a broken feature.
     */
    fun propose(
        call: BuddyToolCallDto,
        type: BuddyActionType,
        userId: UUID,
    ): BuddyActionService.ProposeOutcome =
        when (type) {
            BuddyActionType.COMPLETE_STEP -> proposeCompleteStep(call, type, userId)
            BuddyActionType.COMPLETE_TASK -> proposeCompleteTask(call, type, userId)
            BuddyActionType.ANSWER_QUESTION -> questions.propose(call, type, UserId(userId))
            BuddyActionType.REQUEST_SKIP -> proposeSkip(call, type, userId)
            BuddyActionType.ADD_PATH_STEP -> proposeAddPathStep(call, type, userId)
            else -> error("${type.toolName} is not a path action")
        }

    /** Runs a confirmed path action. Each underlying `/me/...` operation owns its own rules. */
    fun perform(
        type: BuddyActionType,
        authId: String,
        request: BuddyActionRequest,
    ): BuddyActionResponse =
        when (type) {
            BuddyActionType.COMPLETE_STEP -> completeStep(authId, request.stepId)
            BuddyActionType.COMPLETE_TASK -> completeTask(authId, request.onboardingTaskId)
            BuddyActionType.ANSWER_QUESTION -> questions.perform(authId, UserId(resolveUserId(authId)), request)
            BuddyActionType.REQUEST_SKIP -> requestSkip(authId, request.stepId, request.reason)
            BuddyActionType.ADD_PATH_STEP -> addPathStep(authId, request)
            else -> error("${type.toolName} is not a path action")
        }

    /**
     * Offers to tick a step of the hire's path off.
     *
     * The button names the step, and the tool result tells the mentor to *ask* rather than to
     * announce: the one thing this must never become is a mentor that marks work done because the
     * conversation went well. Only the hire knows whether they did it.
     */
    private fun proposeCompleteStep(
        call: BuddyToolCallDto,
        type: BuddyActionType,
        userId: UUID,
    ): BuddyActionService.ProposeOutcome {
        val stepId = call.uuidArgument("step_id")
            ?: return notProposed(
                "No step_id was provided. Read get_my_onboarding_path and pass the step_id of the " +
                    "step they mean.",
            )
        val step = buddyPathTools.findStep(userId, stepId)
            ?: return notProposed(
                "No step of this hire's own path has that id. Read get_my_onboarding_path again — an " +
                    "id from anywhere else is not theirs to tick off.",
            )

        val refusal = when {
            step.status == StepStatus.FINISHED ->
                "“${step.title}” is already done, so there is nothing to tick. Tell them it is " +
                    "already behind them."
            step.status == StepStatus.SKIPPED ->
                "“${step.title}” was skipped, so it cannot be completed. If that was wrong, their PM " +
                    "is the one who can undo it."
            step.locked ->
                "“${step.title}” is locked: something it waits on is not finished. Say what it is " +
                    "waiting on rather than offering to tick it."
            else -> null
        }
        if (refusal != null) return notProposed(refusal)

        // Finishing a step withdraws a skip request still waiting on the PM. Allowed -- somebody who
        // did the step anyway should be able to close it -- but never without saying so, on the
        // button and to the mentor, because nothing else would tell them the request is gone.
        val pendingSkip = step.hasPendingSkip()
        val withdraws = if (pendingSkip) {
            " They asked their PM to skip this step and nobody has decided yet: finishing it withdraws " +
                "that request, so say so plainly before they click."
        } else {
            ""
        }

        return BuddyActionService.ProposeOutcome(
            toolResult = "Proposed to the hire: mark “${step.title}” as done. They see a confirm " +
                "button and nothing changes unless they click it. Ask whether they have actually " +
                "done it — never say that it is done.$withdraws",
            proposal = BuddyActionService.BuddyActionProposal(
                action = type.toolName,
                label = if (pendingSkip) {
                    "Mark “${step.title}” as done (withdraws your skip request)"
                } else {
                    "Mark “${step.title}” as done"
                },
                question = null,
                stepId = step.id,
            ),
        )
    }

    /**
     * Offers to send the hire's skip request for one step to their PM.
     *
     * The same request the step page files, reached from a conversation: the mentor's part is
     * helping the hire say *why*, because a reason the PM can act on is the difference between a
     * request that is decided and one that sits. The reason goes out in the hire's name, so the
     * proposal carries all of it and the button shows it before anything is sent.
     *
     * Checked here rather than left to the route, so the mentor hears "already asked" or "already
     * done" while it can still say something useful about it.
     */
    private fun proposeSkip(
        call: BuddyToolCallDto,
        type: BuddyActionType,
        userId: UUID,
    ): BuddyActionService.ProposeOutcome {
        val stepId = call.uuidArgument("step_id")
            ?: return notProposed(
                "No step_id was provided. Read get_my_onboarding_path and pass the step_id of the " +
                    "step they want to skip.",
            )
        val step = buddyPathTools.findStep(userId, stepId)
            ?: return notProposed(
                "No step of this hire's own path has that id. Read get_my_onboarding_path again.",
            )
        val reason = call.textArgument("reason")
        val previous = step.skip

        val refusal = when {
            step.status == StepStatus.FINISHED ->
                "“${step.title}” is already done, so there is nothing to skip."
            step.status == StepStatus.SKIPPED ->
                "“${step.title}” is already skipped — their PM accepted it."
            step.hasPendingSkip() ->
                "They already asked to skip “${step.title}” and their PM has not decided yet. A second " +
                    "request cannot be sent; they can change the reason on the step's own page " +
                    "(${BuddyPathTools.STEP_PAGE_LINK}${step.id})."
            reason.isBlank() ->
                "No reason was provided. Their PM decides on the reason, so ask why they want to skip " +
                    "it — already know it, not relevant to their role, covered elsewhere — and put " +
                    "that into a sentence or two before offering this again."
            else -> null
        }
        if (refusal != null) return notProposed(refusal)

        // A declined request can be asked again, and the PM said why the first time. The new reason
        // should answer that, or it will be declined for the same thing.
        val declinedBefore = previous
            ?.takeIf { it.accepted == false }
            ?.let { declined ->
                " Their PM declined an earlier request" +
                    (declined.reviewComment?.takeIf { it.isNotBlank() }?.let { " with: “$it”" } ?: "") +
                    " — make sure this reason addresses that."
            }.orEmpty()

        return BuddyActionService.ProposeOutcome(
            toolResult = "Proposed to the hire: ask their PM to skip “${step.title}”, with the reason " +
                "“$reason”. They see a confirm button showing that reason, and nothing is sent unless " +
                "they click it. Their PM decides; until then the step stays on their path, and if it " +
                "is accepted it counts as done and unlocks what waits on it. Do not promise it will " +
                "be accepted.$declinedBefore",
            proposal = BuddyActionService.BuddyActionProposal(
                action = type.toolName,
                label = "Ask your PM to skip “${step.title}”",
                question = null,
                stepId = step.id,
                reason = reason,
            ),
        )
    }

    /** Files the confirmed skip request through the hire's own route, which owns every rule about it. */
    private fun requestSkip(authId: String, stepId: UUID?, reason: String?): BuddyActionResponse {
        if (stepId == null || reason.isNullOrBlank()) {
            return BuddyActionResponse(ok = false, message = "No skip request was proposed to send.")
        }
        val step = buddyPathTools.findStep(resolveUserId(authId), stepId)
            ?: return BuddyActionResponse(ok = false, message = "That step isn't on your path.")

        onboardingSkipService.createOnboardingSkipForMe(authId, stepId, CreateOnboardingSkipRequest(reason = reason))
        return BuddyActionResponse(
            ok = true,
            message = "Sent — your PM will decide on skipping “${step.title}”. Until then it stays on " +
                "your path; you can change or withdraw the reason on the step's page.",
        )
    }

    /**
     * Offers to add a step the conversation produced to a phase of the hire's own path.
     *
     * The one action here that writes something the model wrote, so the line is worth restating: it
     * adds to the hire's copy, their PM's blueprint is untouched, and the hire can edit or delete it
     * on their own page like any other step.
     *
     * A description is required rather than optional. A title on its own is a step somebody has to
     * guess at, and the phase it lands in is usually one that came back empty — precisely the case
     * where the hire has nothing else to go on.
     */
    private fun proposeAddPathStep(
        call: BuddyToolCallDto,
        type: BuddyActionType,
        userId: UUID,
    ): BuddyActionService.ProposeOutcome {
        val phaseId = call.uuidArgument("phase_id")
            ?: return notProposed(
                "No phase_id was provided. Read get_my_onboarding_path and pass the phase_id of the " +
                    "phase the step belongs in.",
            )
        val phase = buddyPathTools.findPhase(userId, phaseId)
            ?: return notProposed(
                "No phase of this hire's own path has that id. Read get_my_onboarding_path again.",
            )
        val title = call.textArgument("title")
        val description = call.textArgument("description")
        val placement = PathStepPlacement.inferred(
            phase,
            call.lenientUuidSetArgument("waits_on"),
            call.lenientUuidSetArgument("unlocks"),
        )

        val refusal = when {
            title.isBlank() -> "No title was provided. Say what the step is, in a few words."
            description.isBlank() ->
                "No description was provided. A step with a title and nothing else is one the hire " +
                    "has to guess at — say what doing it involves, then offer it again."
            phase.steps.any { it.title.trim().equals(title, ignoreCase = true) } ->
                "“$title” is already a step of “${phase.title}”, so nothing needs adding. Point them " +
                    "at the one that is there."
            else -> reopensFinishedPhase(userId, phase) ?: placement.problem()
        }
        if (refusal != null) return notProposed(refusal)

        val where = placement.describe()
        val connection = if (placement.isConnected) {
            " It goes $where in the phase's graph: it opens once what it waits on is done, and what " +
                "it unlocks now waits on it."
        } else {
            " It is not connected to anything, so it is open straight away and is never what comes " +
                "next — if it belongs somewhere in their path, pass waits_on and unlocks."
        }
        val entry = if (placement.entryInferred) {
            " You passed no waits_on, so it opens after what the path says it should (the button " +
                "names it) — if that is not where they meant, offer it again with waits_on."
        } else {
            ""
        }
        val relocks = placement
            .relocksStarted()
            .takeIf { it.isNotEmpty() }
            ?.let {
                " ${it.joinToString(", ")} is already started and will lock again until this step is " +
                    "done — say so before they click."
            }.orEmpty()

        return BuddyActionService.ProposeOutcome(
            toolResult = "Proposed to the hire: add the step “$title” to the phase " +
                "“${phase.title}” of their own path. They see a confirm button; nothing is added " +
                "unless they click it. This changes their copy only — their PM's blueprint is " +
                "untouched — and they can edit or remove it afterwards. Say what the step is for " +
                "and where it goes before you offer it.$connection$entry$relocks",
            proposal = BuddyActionService.BuddyActionProposal(
                action = type.toolName,
                label = if (where.isEmpty()) "Add “$title” to your path" else "Add “$title” $where",
                question = null,
                phaseId = phase.id,
                title = title,
                description = description,
                waitsOnIds = placement.waitsOn.toList(),
                unlocksIds = placement.unlocks.toList(),
            ),
        )
    }

    /**
     * Why adding a step to [phase] would take away something the hire already has, or null.
     *
     * A finished phase is what unlocked every phase that waits on it. A new open step makes it
     * unfinished again, and those phases lock -- a hire asking for one extra step would find the phase
     * they were working in shut. The page's own "add step" has the same effect, which is exactly why
     * the mentor should not reach for it without knowing.
     */
    private fun reopensFinishedPhase(userId: UUID, phase: GetOnboardingPhaseForUserResponse): String? {
        val finished = (phase.steps.isNotEmpty() || phase.questions.isNotEmpty()) &&
            phase.steps.all { it.status == StepStatus.FINISHED || it.status == StepStatus.SKIPPED } &&
            phase.questions.all { it.status == QuestionStatus.PASSED }
        if (!finished) return null
        val waiting = buddyPathTools.phasesOf(userId).filter { phase.id in it.blockerIds }
        if (waiting.isEmpty()) return null
        return "“${phase.title}” is finished, and ${waiting.joinToString(", ") { "“${it.title}”" }} " +
            "waits on it: a new step there would lock that again until it is done. Put the step in the " +
            "phase they are standing in instead."
    }

    /**
     * Offers to tick one line off the checklist of a step.
     *
     * The finer of the two claims, and the reason both exist. A hire who says "I have done the first
     * two" has not finished the step, and a mentor holding only `complete_step` would either overstate
     * that or drop it. The read tool carries the checklist of the step they are on, which is where the
     * task_id comes from.
     */
    private fun proposeCompleteTask(
        call: BuddyToolCallDto,
        type: BuddyActionType,
        userId: UUID,
    ): BuddyActionService.ProposeOutcome {
        val taskId = call.uuidArgument("task_id")
            ?: return notProposed(
                "No task_id was provided. Read get_my_onboarding_path for the checklist of the step " +
                    "they are on and pass the task_id of the line they mean.",
            )
        val task = findTask(userId, taskId)
            ?: return notProposed(
                "No checklist line of this hire's own path has that id. Read get_my_onboarding_path " +
                    "again — it only carries the checklist of the step they are on, and a line of some " +
                    "other step is not in front of you.",
            )
        if (task.finished) {
            return notProposed(
                "“${task.title}” is already ticked off. Tell them it is already done rather than " +
                    "offering it again.",
            )
        }
        // The task route does not check locks; the page does, by never opening a locked step.
        if (buddyPathTools.findStep(userId, task.stepId)?.locked == true) {
            return notProposed(
                "The step that “${task.title}” belongs to is locked, so nothing on its checklist can be " +
                    "ticked yet. Say what the step is waiting on instead.",
            )
        }

        return BuddyActionService.ProposeOutcome(
            toolResult = "Proposed to the hire: tick “${task.title}” off their checklist. They see a " +
                "confirm button and nothing changes unless they click it. Ask whether they have done " +
                "it; ticking a line because the conversation covered it is not the same thing.",
            proposal = BuddyActionService.BuddyActionProposal(
                action = type.toolName,
                label = "Tick off “${task.title}”",
                question = null,
                onboardingTaskId = task.id,
            ),
        )
    }

    /**
     * One checklist line of the hire's own path, or null.
     *
     * Ownership is the *step's*: a line belongs to this hire exactly when the step it hangs on is on
     * their path, which [BuddyPathTools.findStep] answers. That is why the unscoped read by id is safe
     * here and would not be on its own.
     */
    private fun findTask(userId: UUID, taskId: UUID): GetOnboardingTaskResponse? {
        val task = runCatching { onboardingTaskService.getOnboardingTaskById(taskId) }.getOrNull()
            ?: return null
        return task.takeIf { buddyPathTools.findStep(userId, it.stepId) != null }
    }

    /**
     * Ticks the confirmed line off.
     *
     * Read first and written back whole, because the endpoint that owns this takes the task as it
     * should now be rather than a patch. Everything else on it is echoed back unchanged: the mentor is
     * changing one tick box, not editing the hire's checklist.
     */
    private fun completeTask(authId: String, taskId: UUID?): BuddyActionResponse {
        if (taskId == null) {
            return BuddyActionResponse(ok = false, message = "No checklist line was proposed to tick off.")
        }
        val task = onboardingTaskService.getOnboardingTaskForMe(authId, taskId)
        if (buddyPathTools.findStep(resolveUserId(authId), task.stepId)?.locked == true) {
            return BuddyActionResponse(
                ok = false,
                message = "That step is locked right now, so its checklist can't be ticked yet.",
            )
        }
        onboardingTaskService.updateOnboardingTaskForMe(
            authId,
            taskId,
            UpdateOnboardingTaskRequest(
                position = task.position,
                title = task.title,
                description = task.description,
                finished = true,
            ),
        )
        return BuddyActionResponse(
            ok = true,
            message = "Ticked “${task.title}” off. The step itself is still yours to finish when you " +
                "are ready — a checklist does not close it.",
        )
    }

    /**
     * Ticks the confirmed step off.
     *
     * The lock is checked again here, because the route underneath does not check it — the hire's
     * page enforces it by never offering the button — and a proposal can sit in a conversation long
     * enough for the path around it to change. Finished and skipped stay with the route, which
     * refuses both with a sentence of its own.
     */
    private fun completeStep(authId: String, stepId: UUID?): BuddyActionResponse {
        if (stepId == null) {
            return BuddyActionResponse(ok = false, message = "No step was proposed to complete.")
        }
        val current = buddyPathTools.findStep(resolveUserId(authId), stepId)
            ?: return BuddyActionResponse(ok = false, message = "That step isn't on your path.")
        if (current.locked) {
            return BuddyActionResponse(
                ok = false,
                message = "“${current.title}” is locked right now — something it waits on isn't finished yet.",
            )
        }
        val step = onboardingStepService.completeOnboardingStepForMe(authId, stepId)
        return BuddyActionResponse(
            ok = true,
            message = "Ticked off “${step.title}”. It is done on your path — if that was too early, " +
                "you can reopen it there.",
        )
    }

    private fun addPathStep(authId: String, request: BuddyActionRequest): BuddyActionResponse {
        val phaseId = request.phaseId
        val title = request.title
        if (phaseId == null || title.isNullOrBlank()) {
            return BuddyActionResponse(ok = false, message = "No step was proposed to add.")
        }
        val userId = resolveUserId(authId)
        val phase = buddyPathTools.findPhase(userId, phaseId)
            ?: return BuddyActionResponse(ok = false, message = "That phase isn't on your path.")

        // Checked again: the path can change between the button and the click, and a placement
        // that was sound then can be a loop or point at something finished now.
        val placement = PathStepPlacement(phase, request.waitsOnIds.toSet(), request.unlocksIds.toSet())
        // The finished-phase rule too: a phase the hire finished after the button was shown must not
        // be reopened by clicking it, locking what they have since started in the phases after it.
        if (placement.problem() != null || reopensFinishedPhase(userId, phase) != null) {
            return BuddyActionResponse(
                ok = false,
                message = "Your path changed since this was suggested, so the step wasn't added — ask me again.",
            )
        }

        val created = onboardingStepPlacementService.createConnectedStepForMe(
            authId = authId,
            phaseId = phaseId,
            request = CreateOnboardingStepRequest(
                // Next to what it waits on (or in front of what it unlocks), so the list reads in the
                // order the graph opens it. The end, when it is connected to nothing.
                position = placement.position(),
                title = title,
                description = request.description.orEmpty(),
                type = StepType.TASK,
                estimatedMinutes = ADDED_STEP_MINUTES,
                // Left empty on purpose: an expected outcome is a promise about what doing the step
                // will leave somebody able to do, and the mentor is not in a position to make one.
                expectedOutcome = "",
            ),
            // Theirs, but not their idea: the card says it came from the buddy, not from their team.
            origin = StepOrigin.BUDDY,
            waitsOn = placement.waitsOn,
            unlocks = placement.unlocks,
        )
        return BuddyActionResponse(
            ok = true,
            message = "Added “${created.title}” to “${phase.title}”. It is on your path now, and it " +
                "is yours — edit it or delete it there like any other step.",
        )
    }

    private fun resolveUserId(authId: String): UUID =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }

    private companion object {
        /**
         * The estimate a step the buddy added carries.
         *
         * Deliberately a constant the mentor cannot choose. How long a piece of work takes somebody
         * else is not something a model is in a position to say, and a confident "45 min" on a step
         * it invented is worse than a modest default the hire can correct on their own page.
         */
        const val ADDED_STEP_MINUTES = 15

        val COMPLETE_STEP_SPEC = BuddyToolSpecDto(
            name = BuddyActionType.COMPLETE_STEP.toolName,
            description = "Offer to mark one step of the hire's onboarding path as done. Read " +
                "get_my_onboarding_path first and pass that step's step_id. This does NOT complete " +
                "anything by itself: the hire sees a confirm button naming the step, and only they " +
                "can click it. Use it when they say they have finished something, and when " +
                "get_my_onboarding_path lists a step as READY TO CLOSE (its checklist is done but " +
                "the step is still open, so what waits on it stays locked) — never because the " +
                "conversation went well, and never to tidy up their path. Only they know whether " +
                "the work is done, so ask; do not announce it as done.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("step_id") {
                        put("type", "string")
                        put("description", "The step_id from get_my_onboarding_path.")
                    }
                }
                putJsonArray("required") { add("step_id") }
            },
        )

        val COMPLETE_TASK_SPEC = BuddyToolSpecDto(
            name = BuddyActionType.COMPLETE_TASK.toolName,
            description = "Offer to tick one line off the checklist of the step the hire is on. Read " +
                "get_my_onboarding_path for the checklist and pass that line's task_id. This does " +
                "NOT tick anything by itself; the hire sees a confirm button naming the line. Use it " +
                "when they say they have done part of a step — that is what this is for, and it is " +
                "why it is separate from complete_step: a step can be finished with lines still open, " +
                "and finishing a step is a bigger claim than ticking a line. Ask; do not tick a line " +
                "off because the conversation covered it.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("task_id") {
                        put("type", "string")
                        put("description", "The task_id from the checklist in get_my_onboarding_path.")
                    }
                }
                putJsonArray("required") { add("task_id") }
            },
        )

        val REQUEST_SKIP_SPEC = BuddyToolSpecDto(
            name = BuddyActionType.REQUEST_SKIP.toolName,
            description = "Offer to send the hire's request to skip one step of their onboarding path " +
                "to their PM. Read get_my_onboarding_path for the step_id. Use it when THEY want to " +
                "skip a step — never suggest skipping to get through the path faster, and never for a " +
                "step just because it looks hard. Their PM decides, so the reason is what matters: " +
                "ask why before you offer this, help them put it into one or two clear sentences " +
                "(what they already know, why it does not apply to their role, where it is covered " +
                "already), and pass that. It goes out in their name, so it must say what they said, " +
                "not what you think. This does NOT send anything by itself; they see a confirm button " +
                "with the reason. Do not promise it will be accepted. A step that already has a " +
                "request waiting cannot get a second one.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("step_id") {
                        put("type", "string")
                        put("description", "The step_id from get_my_onboarding_path.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put(
                            "description",
                            "Why they want to skip it, in one or two sentences their PM can decide on, " +
                                "written as the hire.",
                        )
                    }
                }
                putJsonArray("required") {
                    add("step_id")
                    add("reason")
                }
            },
        )

        val ADD_PATH_STEP_SPEC = BuddyToolSpecDto(
            name = BuddyActionType.ADD_PATH_STEP.toolName,
            description = "Offer to add one step to a phase of the hire's OWN copy of their " +
                "onboarding path. Read get_my_onboarding_path for the phase_id. This does NOT add " +
                "anything by itself; the hire confirms, and afterwards the step is theirs to edit, " +
                "reorder or delete. It never touches their PM's blueprint — the curriculum is the " +
                "PM's, and you are not editing it. Use it for three things and little else: a phase " +
                "that came back empty, where the two of you have worked out something concrete it " +
                "should contain; something real the hire is stuck on that their path does not " +
                "mention, so it stops living in a conversation that is gone tomorrow; and a " +
                "knowledge question they got wrong, once you have gone through the material, when " +
                "what they missed is bigger than one explanation — then one short refresher step in " +
                "that question's phase, saying what to revisit and where, and never the answer. " +
                "Offer it; do not add one after every wrong answer. And whenever the hire ASKS you to " +
                "add a step: it is their copy, so their asking is enough — offer it, do not argue. Give a title " +
                "of a few words and a description saying what doing it involves. Do not offer a " +
                "step for something already on their path, do not add several at once, and do not " +
                "add one just to have added something.\n" +
                "WHERE IT GOES. A phase is a dependency graph, not a list: an item opens once " +
                "everything it waits on is done. A step added with no connections is open at once, " +
                "floats unconnected in their graph view and is never what comes next. So place it: " +
                "waits_on = the ids it should open after, unlocks = the ids that should wait on it. " +
                "To put it in as the NEXT thing, waits_on is the item they are on or just finished, " +
                "and unlocks is what currently waits on that item (the path read lists it under " +
                "\"opens\"). A refresher for a question they missed goes before that question: " +
                "unlocks = [that question_id]. Leave both empty only for a step that genuinely " +
                "depends on nothing and holds nothing up.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("phase_id") {
                        put("type", "string")
                        put("description", "The phase_id from get_my_onboarding_path.")
                    }
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "What the step is, in a few words.")
                    }
                    putJsonObject("description") {
                        put("type", "string")
                        put(
                            "description",
                            "What doing it involves, in one or two sentences the hire can act on.",
                        )
                    }
                    putJsonObject("waits_on") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put(
                            "description",
                            "step_id/question_id values in the same phase that must be done before " +
                                "this step opens.",
                        )
                    }
                    putJsonObject("unlocks") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put(
                            "description",
                            "step_id/question_id values in the same phase that should wait on this " +
                                "step. They stop waiting directly on anything in waits_on.",
                        )
                    }
                }
                putJsonArray("required") {
                    add("phase_id")
                    add("title")
                    add("description")
                }
            },
        )
    }
}
