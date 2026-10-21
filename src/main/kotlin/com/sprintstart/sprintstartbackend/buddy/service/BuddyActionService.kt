package com.sprintstart.sprintstartbackend.buddy.service

import com.sprintstart.sprintstartbackend.arrival.external.HireArrivalApi
import com.sprintstart.sprintstartbackend.assistant.external.BuddyActionResponse
import com.sprintstart.sprintstartbackend.assistant.external.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.assistant.external.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.assistant.external.HireAction
import com.sprintstart.sprintstartbackend.assistant.external.HireActionContext
import com.sprintstart.sprintstartbackend.assistant.external.HireActionOutcome
import com.sprintstart.sprintstartbackend.assistant.external.HireActionProposal
import com.sprintstart.sprintstartbackend.assistant.external.HireActionRequest
import com.sprintstart.sprintstartbackend.assistant.external.HireActionScope
import com.sprintstart.sprintstartbackend.assistant.external.HireProject
import com.sprintstart.sprintstartbackend.buddy.external.enums.BuddyActionType
import com.sprintstart.sprintstartbackend.knowledge.external.HireKnowledgeApi
import com.sprintstart.sprintstartbackend.shared.ids.AuthId
import com.sprintstart.sprintstartbackend.shared.ids.ProjectId
import com.sprintstart.sprintstartbackend.shared.ids.UserId
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The buddy's *action* tools: the buddy stops only advising and starts doing — always on the hire's
 * explicit confirmation.
 *
 * The one rule that makes this safe: a tool call never mutates. When the AI reasoner calls an
 * action tool during a turn, [propose] resolves the concrete action and hands back a proposal the
 * frontend renders as a confirm button — nothing changes. Only [perform], reached by a separate
 * confirm request after the hire clicks, runs the real `/me/...` operation. Both ends read the same
 * [BuddyActionType] catalog, so the proposal and the execution can never name different actions.
 *
 * Every action is executed strictly on behalf of the resolved caller and scoped to the caller's own
 * project, re-resolved server-side at confirm time — a client can never confirm an action against a
 * project the buddy did not scope it to, nor act as another hire.
 *
 * Six wrapped actions live here, each with a propose and a perform half — hence the suppressed
 * function count. The five that put the mentor's *own words* on a board are in
 * [BuddyBoardWriteActions], and the ones that walk the hire's path are in [BuddyPathActions]: what
 * the actions here do is wrap an existing `/me/...` operation one for one, and those do something
 * different enough to be worth reading on their own.
 */
@Service
@Suppress("TooManyFunctions")
class BuddyActionService(
    private val hireKnowledgeApi: HireKnowledgeApi,
    private val userApi: UserApi,
    private val hireArrivalApi: HireArrivalApi,
    private val buddyPathActions: BuddyPathActions,
    private val boardWrites: BuddyBoardWriteActions,
    private val hireActions: List<HireAction>,
) {
    /**
     * The action tools the AI reasoner is told it may propose, alongside the read-only tools.
     *
     * Per hire rather than globally, for the same reason [BuddyToolExecutor.toolSpecs] is: the
     * path actions have a subject that may not exist. A mentor handed `complete_step` for somebody
     * with no onboarding path will offer to tick a step off a plan they have not got.
     */
    fun actionSpecs(userId: UUID): List<BuddyToolSpecDto> =
        listOf(FLAG_TO_PM_SPEC, REQUEST_ATTESTATION_SPEC, SET_GITHUB_LOGIN_SPEC) +
            hireActions.filter { it.isMountedFor(UserId(userId)) }.map { it.spec } +
            buddyPathActions.specs(userId) + boardWrites.specs()

    /** Whether [toolName] is an action tool (handled by [propose]) rather than a read-only tool. */
    fun isAction(toolName: String): Boolean =
        BuddyActionType.fromToolName(toolName) != null || hireActions.any { it.spec.name == toolName }

    /**
     * Turns an action tool call into a proposal for the hire to confirm — without mutating.
     *
     * Returns the [HireActionOutcome.toolResult] to feed back to the AI (so it relays "I can do X —
     * confirm below", never "done"), and a [HireActionOutcome.proposal] to emit to the client when the
     * action can be offered. When it can't (no project, or a missing question), there is no proposal
     * and the tool result is the legible reason.
     */
    @Suppress("CyclomaticComplexMethod") // The person-scoped gates first, then one branch per action.
    fun propose(call: BuddyToolCallDto, userId: UUID): HireActionOutcome {
        hireActions.firstOrNull { it.spec.name == call.name }?.let {
            return proposeContributed(it, call, UserId(userId))
        }
        val type = BuddyActionType.fromToolName(call.name)
            ?: return HireActionOutcome("Unknown action: ${call.name}.", null)

        // Before the project gate: a GitHub login is a fact about a person, and a hire on day one may be on
        // no project yet.
        if (type == BuddyActionType.SET_GITHUB_LOGIN) {
            return proposeGithubLogin(call, type)
        }

        // Before the project gate as well: an onboarding path belongs to a person, not to a project.
        if (buddyPathActions.handles(type)) {
            return buddyPathActions.propose(call, type, userId)
        }

        val project = when (val resolution = resolveProject(userId)) {
            is ProjectResolution.Resolved -> resolution
            ProjectResolution.None ->
                return HireActionOutcome(
                    "The hire is not on a project yet, so there is nothing to ${type.gerund()} for them.",
                    null,
                )
            ProjectResolution.Ambiguous ->
                return HireActionOutcome(
                    "The hire is onboarding on more than one project. Ask which one before offering to " +
                        "${type.gerund()}.",
                    null,
                )
        }

        return when (type) {
            BuddyActionType.FLAG_TO_PM -> {
                val question = call.textArgument("question")
                if (question.isBlank()) {
                    HireActionOutcome(
                        "No question was provided to flag. Ask the hire what they want answered first.",
                        null,
                    )
                } else {
                    proposed(type, project.name, question = question)
                }
            }
            BuddyActionType.REQUEST_ATTESTATION -> proposeAttestation(call, type, project.name)
            else ->
                if (boardWrites.handles(type)) {
                    boardWrites.propose(
                        call,
                        type,
                        BuddyBoardWriteActions.Scope(userId, project.projectId, project.name),
                    )
                } else {
                    proposed(type, project.name, question = null)
                }
        }
    }

    /**
     * Offers to record the GitHub account the hire named, without a project and without mutating.
     *
     * Only the *shape* is checked here — that a username was actually supplied. Whether it is a
     * possible GitHub name, and whether somebody else already claims it, stay with
     * [com.sprintstart.sprintstartbackend.user.service.GithubLoginService] at confirm time: it owns
     * those rules for every other writer, and a second opinion here would be one more place for
     * them to drift.
     */
    private fun proposeGithubLogin(call: BuddyToolCallDto, type: BuddyActionType): HireActionOutcome {
        val login = call.textArgument("login")
        if (login.isBlank()) {
            return HireActionOutcome(
                "No username was provided. Ask the hire for their GitHub username before offering " +
                    "to save it.",
                null,
            )
        }
        return HireActionOutcome(
            toolResult = "Proposed to the hire: save “$login” as their GitHub username. They will " +
                "see a confirm button; it is saved only if they click it. Offer it — do not claim " +
                "it is done.",
            proposal = HireActionProposal(
                action = type.toolName,
                label = type.label,
                question = null,
                githubLogin = login,
            ),
        )
    }

    /**
     * Records the confirmed username through the one writer that owns it.
     *
     * ### Why this action exists
     *
     * A hire testing the buddy said *"my GitHub username is …"* and nothing was recorded, because
     * no tool could. The model went looking through the corpus for how to store one. Meanwhile the
     * login is what artifact verification compares a pull request's author against, so the failure
     * is not cosmetic: work they really do goes on not being credited, and they read as calm rather
     * than blocked.
     *
     * [com.sprintstart.sprintstartbackend.user.service.GithubLoginService] stays the single writer —
     * the conversation is a second *entry point*, never a second source of truth. That is also what
     * keeps the normalisation, the uniqueness check and the rule that a changed login discards
     * the old verification verdict true here without restating any of them.
     */
    private fun setGithubLogin(authId: String, login: String?): BuddyActionResponse {
        if (login.isNullOrBlank()) {
            return BuddyActionResponse(ok = false, message = "No username was proposed to save.")
        }
        val userId = userApi.getUserIdByAuthId(authId).orElse(null)
            ?: return BuddyActionResponse(ok = false, message = "I couldn't find your account.")

        val saved = userApi.setGithubLogin(userId, login)
        return BuddyActionResponse(
            ok = true,
            message = "Saved — your work will be attributed to @$saved from now on. If that is not " +
                "right, you can change it in Settings any time.",
        )
    }

    /**
     * Both halves of an attestation proposal must be real before the hire sees a button: what work,
     * and which teammate. A missing one comes back as guidance the buddy can act on rather than a
     * proposal it cannot honour.
     */
    private fun proposeAttestation(
        call: BuddyToolCallDto,
        type: BuddyActionType,
        projectName: String,
    ): HireActionOutcome {
        val title = call.textArgument("title")
        val attesterId = call.uuidArgument("attester_id")
        return when {
            title.isBlank() ->
                HireActionOutcome("No title was provided. Ask the hire what work they want confirmed.", null)
            attesterId == null ->
                HireActionOutcome(
                    "No attester_id was provided. Read get_teammates and ask the hire which of " +
                        "them should confirm it, then pass that person's id.",
                    null,
                )
            else -> proposed(type, projectName, question = null, title = title, attesterId = attesterId)
        }
    }

    /**
     * Runs a confirmed action on behalf of [jwt]'s user, scoped to their re-resolved project.
     *
     * Never throws for a handled outcome: an expected precondition failure ("no current task",
     * "not a member") comes back as `ok = false` with a legible message, so the buddy always has a
     * line to relay. Only a genuinely unexpected failure propagates. Blocking work runs on the IO
     * dispatcher; opening orientation is itself suspend and manages its own transactions.
     */
    suspend fun perform(request: HireActionRequest, jwt: Jwt): BuddyActionResponse {
        val authId = jwt.subject

        hireActions.firstOrNull { it.spec.name == request.action }?.let {
            return performContributed(it, request, AuthId(authId))
        }
        val type = BuddyActionType.fromToolName(request.action)
            ?: return BuddyActionResponse(ok = false, message = "That action isn't recognised.")

        // Same reason as in propose: not project-scoped, so not behind the project gate.
        if (type == BuddyActionType.SET_GITHUB_LOGIN) {
            return try {
                withContext(Dispatchers.IO) { setGithubLogin(authId, request.githubLogin) }
            } catch (ex: ResponseStatusException) {
                // 400 for a syntactically impossible username, 409 when another hire already
                // claims it. Both are things the hire can act on, so they come back as the
                // sentence GithubLoginService wrote rather than as a failed confirm.
                BuddyActionResponse(ok = false, message = ex.reason ?: "That username could not be saved.")
            }
        }

        // And again: a path belongs to a person, not to a project. See the note in `propose`.
        if (buddyPathActions.handles(type)) {
            return try {
                withContext(Dispatchers.IO) { buddyPathActions.perform(type, authId, request) }
            } catch (ex: ResponseStatusException) {
                // A precondition the underlying route owns (a step that is already finished, a
                // question that is not theirs). Relay its sentence rather than failing the confirm.
                BuddyActionResponse(ok = false, message = ex.reason ?: "That didn't go through.")
            }
        }

        val context = withContext(Dispatchers.IO) { resolveContext(authId) }
        val resolved = when (context) {
            is CallerContext.Resolved -> context
            CallerContext.NoProject ->
                return BuddyActionResponse(ok = false, message = "You're not on a project yet.")
            CallerContext.MultipleProjects ->
                return BuddyActionResponse(
                    ok = false,
                    message = "You're onboarding on more than one project — tell me which one and I'll set it up.",
                )
        }

        return try {
            dispatch(type, resolved, authId, request)
        } catch (ex: ResponseStatusException) {
            // A precondition the underlying route enforces (not a member, blank question, …). Relay
            // its reason rather than failing the whole confirm with an HTTP error.
            BuddyActionResponse(ok = false, message = ex.reason ?: "That didn't go through — try again in a moment.")
        }
    }

    /** Runs the confirmed action itself. Suspend operations manage their own transactions. */
    private suspend fun dispatch(
        type: BuddyActionType,
        resolved: CallerContext.Resolved,
        authId: String,
        request: HireActionRequest,
    ): BuddyActionResponse =
        when (type) {
            else -> withContext(Dispatchers.IO) {
                when (type) {
                    BuddyActionType.FLAG_TO_PM -> flagToPm(authId, resolved.projectId, request.question)
                    BuddyActionType.REQUEST_ATTESTATION ->
                        requestAttestation(resolved, request.title, request.attesterId)
                    BuddyActionType.PLACE_CHECKLIST,
                    BuddyActionType.AMEND_CHECKLIST,
                    BuddyActionType.PLACE_NOTE,
                    BuddyActionType.TICK_CHECKLIST_ITEMS,
                    BuddyActionType.REWORD_CHECKLIST_ITEM,
                    BuddyActionType.PLACE_LINK,
                    BuddyActionType.EDIT_NOTE,
                    BuddyActionType.EDIT_LINK,
                    BuddyActionType.EDIT_CHECKLIST,
                    BuddyActionType.DISMISS_CARDS,
                    BuddyActionType.REORDER_CARDS,
                    -> boardWrites.perform(
                        type,
                        resolved.userId,
                        resolved.projectId,
                        BuddyBoardWriteActions.BoardWritePayload(
                            checklistTitle = request.checklistTitle,
                            checklistItems = request.checklistItems,
                            cardId = request.cardId,
                            noteText = request.noteText,
                            lineBefore = request.lineBefore,
                            lineAfter = request.lineAfter,
                            linkUrl = request.linkUrl,
                            linkLabel = request.linkLabel,
                            cardIds = request.cardIds,
                            basedOn = request.basedOn,
                        ),
                    )
                    // Not project-scoped, so these return before the project gate this dispatch
                    // sits behind.
                    BuddyActionType.SET_GITHUB_LOGIN,
                    BuddyActionType.COMPLETE_STEP,
                    BuddyActionType.COMPLETE_TASK,
                    BuddyActionType.ANSWER_QUESTION,
                    BuddyActionType.ADD_PATH_STEP,
                    BuddyActionType.REQUEST_SKIP,
                    -> error("handled above")
                }
            }
        }

    /**
     * Asks a named colleague to confirm a piece of the hire's work.
     *
     * The buddy relays; it never confirms. Every rule that makes an attestation worth anything —
     * not the hire, on this project — is enforced by [HireArrivalApi.requestAttestation], so a buddy that got the
     * person wrong produces a legible refusal rather than weak evidence.
     */
    private fun requestAttestation(
        resolved: CallerContext.Resolved,
        title: String?,
        attesterId: UUID?,
    ): BuddyActionResponse {
        if (title.isNullOrBlank() || attesterId == null) {
            return BuddyActionResponse(ok = false, message = "I need to know what work to confirm and who to ask.")
        }
        return try {
            val stored = hireArrivalApi.requestAttestation(
                hireId = UserId(resolved.userId),
                projectId = ProjectId(resolved.projectId),
                title = title,
                attesterId = UserId(attesterId),
            )
            BuddyActionResponse(
                ok = true,
                message = "Asked them to confirm “$stored”. " +
                    "It counts once they do — you will see it in what you have shown.",
            )
        } catch (e: ResponseStatusException) {
            // A handled precondition ("not on this project", "that is you") is a sentence the buddy
            // can relay, not a stack trace.
            BuddyActionResponse(ok = false, message = e.reason ?: "That request could not be filed.")
        }
    }

    private fun flagToPm(authId: String, projectId: UUID, question: String?): BuddyActionResponse {
        val trimmed = question?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return BuddyActionResponse(ok = false, message = "I need the question to flag — tell me what to ask.")
        }
        hireKnowledgeApi.escalate(AuthId(authId), ProjectId(projectId), trimmed)
        return BuddyActionResponse(
            ok = true,
            message = "Flagged to your PM. Their answer will show up here once they reply.",
        )
    }

    /**
     * What the model is told when it offers an action, and what it must not do afterwards.
     *
     * The second half of this text is not decoration. **The model never finds out what happened to
     * a proposal**: the hire confirms out-of-band, the outcome lands in the client's own message
     * state, and nothing about it comes back into the conversation. So the model is blind here, and
     * a blind model that believes a button is still on screen starts pointing at it — a hire once
     * spent a whole exchange being told to click a button that had been spent on a failed confirm
     * several turns earlier, ending with the mentor guessing aloud where on their screen it might
     * be hiding. Telling somebody they must be missing something they can see is not there is the
     * worst thing this surface can do: it makes them distrust the app rather than the sentence.
     *
     * Hence the rule the text carries — never describe the button, and when the hire says it did
     * not work, call the tool again instead of insisting. A fresh proposal costs one click and puts
     * a real control back on screen; describing the old one cannot.
     */
    private fun proposed(
        type: BuddyActionType,
        projectName: String,
        question: String?,
        taskId: UUID? = null,
        title: String? = null,
        attesterId: UUID? = null,
        githubLogin: String? = null,
        checklistTitle: String? = null,
        checklistItems: List<String>? = null,
        cardId: UUID? = null,
        noteText: String? = null,
    ): HireActionOutcome =
        HireActionOutcome(
            toolResult = "Proposed to the hire on $projectName: “${type.label}”. They will see a confirm " +
                "button; the action runs only if they click it. Offer it — do not claim it is done. " +
                "You will never be told whether they confirmed it or what came of it, and a confirmed " +
                "proposal leaves the screen. So do not describe the button, tell them where to find " +
                "it, or ask them to click it again. If they say nothing happened or they cannot see " +
                "it, believe them and call this tool again to offer it afresh.",
            proposal = HireActionProposal(
                action = type.toolName,
                label = type.label,
                question = question,
                taskId = taskId,
                title = title,
                attesterId = attesterId?.toString(),
                githubLogin = githubLogin,
                checklistTitle = checklistTitle,
                checklistItems = checklistItems,
                cardId = cardId,
                noteText = noteText,
            ),
        )

    /** Offers a feature's [action], resolving the hire's one project first when the action needs it. */
    private fun proposeContributed(action: HireAction, call: BuddyToolCallDto, hire: UserId): HireActionOutcome {
        val project = if (action.scope == HireActionScope.PERSON) {
            null
        } else {
            when (val resolution = resolveProject(hire.value)) {
                is ProjectResolution.Resolved -> HireProject(ProjectId(resolution.projectId), resolution.name)
                ProjectResolution.None -> return HireActionOutcome(
                    "The hire is not on a project yet, so there is nothing to ${action.gerund} for them.",
                    null,
                )
                ProjectResolution.Ambiguous -> return HireActionOutcome(
                    "The hire is onboarding on more than one project. Ask which one before offering to " +
                        "${action.gerund}.",
                    null,
                )
            }
        }
        return action.propose(call, HireActionContext(hire, project))
    }

    /**
     * Runs a feature's confirmed [action] for the caller behind [authId], with the same project gate as
     * [proposeContributed]. A precondition the action's service refuses comes back as its sentence.
     */
    private suspend fun performContributed(
        action: HireAction,
        request: HireActionRequest,
        authId: AuthId,
    ): BuddyActionResponse {
        val userId = withContext(Dispatchers.IO) { userApi.getUserIdByAuthId(authId.value).orElse(null) }
            ?: return BuddyActionResponse(ok = false, message = "I couldn't find your account.")
        val project = if (action.scope == HireActionScope.PERSON) {
            null
        } else {
            when (val resolution = withContext(Dispatchers.IO) { resolveProject(userId) }) {
                is ProjectResolution.Resolved -> HireProject(ProjectId(resolution.projectId), resolution.name)
                ProjectResolution.None -> return BuddyActionResponse(
                    ok = false,
                    message = "You're not on a project yet.",
                )
                ProjectResolution.Ambiguous -> return BuddyActionResponse(
                    ok = false,
                    message = "You're onboarding on more than one project — tell me which one and I'll set it up.",
                )
            }
        }
        val context = HireActionContext(UserId(userId), project)
        return try {
            withContext(Dispatchers.IO) { action.perform(request, context, authId) }
        } catch (ex: ResponseStatusException) {
            BuddyActionResponse(ok = false, message = ex.reason ?: "That didn't go through — try again in a moment.")
        }
    }

    private fun resolveProject(userId: UUID): ProjectResolution {
        val projects = userApi
            .getUsersByIds(listOf(userId))
            .firstOrNull()
            ?.projects
            .orEmpty()
        return when {
            projects.isEmpty() -> ProjectResolution.None
            projects.size > 1 -> ProjectResolution.Ambiguous
            else -> projects.first().let { ProjectResolution.Resolved(it.projectId, it.name) }
        }
    }

    /** Resolves the caller's id and single onboarding project in one read, for the confirm path. */
    private fun resolveContext(authId: String): CallerContext {
        val userId = resolveUserId(authId)
        return when (val resolution = resolveProject(userId)) {
            is ProjectResolution.Resolved -> CallerContext.Resolved(userId, resolution.projectId, resolution.name)
            ProjectResolution.None -> CallerContext.NoProject
            ProjectResolution.Ambiguous -> CallerContext.MultipleProjects
        }
    }

    private fun resolveUserId(authId: String): UUID =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }

    /** A verb phrase for the reason lines, e.g. "claim a goal", "flag this to a PM". */
    @Suppress("CyclomaticComplexMethod") // One flat branch per action type, and the enum is exhaustive.
    private fun BuddyActionType.gerund(): String =
        when (this) {
            BuddyActionType.FLAG_TO_PM -> "flag this to a PM"
            BuddyActionType.REQUEST_ATTESTATION -> "ask somebody to confirm your work"
            // The next three never reach the no-project reason lines: they are not project-scoped.
            BuddyActionType.SET_GITHUB_LOGIN -> "save a GitHub username"
            BuddyActionType.COMPLETE_STEP -> "tick a step off your path"
            BuddyActionType.COMPLETE_TASK -> "tick a line off your checklist"
            BuddyActionType.ANSWER_QUESTION -> "send an answer to a question"
            BuddyActionType.ADD_PATH_STEP -> "add a step to your path"
            BuddyActionType.REQUEST_SKIP -> "ask your PM to skip a step"
            else -> BOARD_WRITE_GERUNDS.getValue(this)
        }

    private sealed interface ProjectResolution {
        data class Resolved(
            val projectId: UUID,
            val name: String,
        ) : ProjectResolution

        data object None : ProjectResolution

        data object Ambiguous : ProjectResolution
    }

    private sealed interface CallerContext {
        data class Resolved(
            val userId: UUID,
            val projectId: UUID,
            val projectName: String,
        ) : CallerContext

        data object NoProject : CallerContext

        data object MultipleProjects : CallerContext
    }

    private companion object {
        /** [gerund] for every board write, in the words the no-project reason lines use. */
        val BOARD_WRITE_GERUNDS = mapOf(
            BuddyActionType.PLACE_CHECKLIST to "keep a checklist on your board",
            BuddyActionType.AMEND_CHECKLIST to "add to a checklist on your board",
            BuddyActionType.PLACE_NOTE to "keep a note on your board",
            BuddyActionType.TICK_CHECKLIST_ITEMS to "tick something off your board",
            BuddyActionType.REWORD_CHECKLIST_ITEM to "reword a line on your board",
            BuddyActionType.PLACE_LINK to "keep a link on your board",
            BuddyActionType.EDIT_NOTE to "update a note on your board",
            BuddyActionType.EDIT_LINK to "update a link on your board",
            BuddyActionType.EDIT_CHECKLIST to "update a list on your board",
            BuddyActionType.DISMISS_CARDS to "clear cards off your board",
            BuddyActionType.REORDER_CARDS to "rearrange your board",
        )

        val FLAG_TO_PM_SPEC = BuddyToolSpecDto(
            name = BuddyActionType.FLAG_TO_PM.toolName,
            description = "Offer to pass something from the hire to their project's PM. Two cases: " +
                "(1) the hire ASKS you to flag, raise or pass something to their PM — a question, a problem, a " +
                "blocker, feedback on their path. Their asking is the reason: offer it straight away, and never " +
                "decide for them that it is not a PM matter or that the docs answer it first. " +
                "(2) Neither the docs nor the canonical answers cover a question, as the last resort when you " +
                "genuinely cannot ground an answer. " +
                "This does NOT send anything — it shows the hire a confirm button, and only they can send it. " +
                "Put what should reach the PM in `question`, phrased clearly and in the hire's sense.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("question") {
                        put("type", "string")
                        put(
                            "description",
                            "What to send to the PM — a question, or what the hire wants them to know — phrased " +
                                "clearly for a person to answer.",
                        )
                    }
                }
                putJsonArray("required") { add("question") }
            },
        )

        val SET_GITHUB_LOGIN_SPEC = BuddyToolSpecDto(
            name = BuddyActionType.SET_GITHUB_LOGIN.toolName,
            description = "Offer to save the hire's GitHub username. Use this the moment they tell " +
                "you one -- 'my GitHub is octocat', 'I'm @octocat on GitHub' -- rather than sending " +
                "them to Settings. It matters: this is the account their pull requests are matched " +
                "against, so until it is set, work they really do is not credited to them and " +
                "nothing says so. Pass exactly what they gave you, without the @. Proposing does " +
                "not save it; they confirm.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("login") {
                        put("type", "string")
                        put("description", "The GitHub username the hire gave, without a leading @.")
                    }
                }
                putJsonArray("required") { add("login") }
            },
        )

        val REQUEST_ATTESTATION_SPEC = BuddyToolSpecDto(
            name = BuddyActionType.REQUEST_ATTESTATION.toolName,
            description = "Offer to ask a named colleague to confirm a piece of work the hire has " +
                "done — a facilitated ceremony, a published plan, anything no system can see. " +
                "This does NOT confirm anything by itself; it shows the hire a confirm button and " +
                "runs only if they click, and the colleague still has to agree. Use for work that " +
                "leaves no trace a tool can read. Read get_teammates first and ask the hire who " +
                "should confirm it — never guess, and never name the hire themselves.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("title") {
                        put("type", "string")
                        put("description", "What the hire did, in their words.")
                    }
                    putJsonObject("attester_id") {
                        put("type", "string")
                        put("description", "The user id of the teammate the hire picked to confirm it.")
                    }
                }
                putJsonArray("required") {
                    add("title")
                    add("attester_id")
                }
            },
        )
    }
}
