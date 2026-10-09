package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BuddyMessageRole
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentMessageDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyCitationDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyOpenRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyOpenStreamEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyStreamEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolSpecDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyVocabularyDto
import com.sprintstart.sprintstartbackend.onboarding.model.ContributionWording
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddySessionFilters
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyTeamMessage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyTeamSession
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toAgentMessage
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.buddy.BuddyMessageResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyTeamMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyTeamSessionRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Team mode: a project manager's buddy conversation about one project's team.
 *
 * The same visit model as the hire's buddy ([BuddyService]) — a durable transcript, a memory note
 * folded in the background, a greeting that opens a visit — with three differences that are the
 * point of it:
 *
 * - **Authorisation per turn.** Every entry point confirms the caller manages the project, through
 *   [UserApi.canManageProject], before anything is read. Losing the assignment refuses the next
 *   turn rather than leaving a manager with tools for a team that is no longer theirs.
 * - **Its own conversation per project.** Team talk never lands in the manager's own onboarding
 *   memory, and one project's note never grounds a turn about another.
 * - **Tools resolved per hop.** `open_area` changes what the next hop may call, so the tool list is
 *   rebuilt on every step of the agent loop instead of once per turn.
 */
@Service
@Suppress("TooManyFunctions") // The same surface as BuddyService — open, read, speak — plus its loop helpers.
class BuddyTeamService(
    private val buddyTeamSessionRepository: BuddyTeamSessionRepository,
    private val buddyTeamMessageRepository: BuddyTeamMessageRepository,
    private val onboardingAiClient: OnboardingAiClient,
    private val buddyTeamTools: BuddyTeamTools,
    private val buddyProposalService: BuddyProposalService,
    private val userApi: UserApi,
    private val buddyCompactionService: BuddyCompactionService,
    private val applicationScope: CoroutineScope,
    private val artifactLookupService: ArtifactLookupService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Returns the current visit of the caller's team conversation about [projectId], oldest first.
     *
     * @throws ResponseStatusException 404 if the user does not exist; 403 if they do not manage the project.
     */
    fun getMessagesForMe(authId: String, projectId: UUID): List<BuddyMessageResponse> {
        val userId = authorize(authId, projectId)
        val session = buddyTeamSessionRepository.findByUserIdAndProjectId(userId, projectId)
            ?: return emptyList()
        val messages = buddyTeamMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id)
        return messages
            .drop(messages.indexOfLast { it.opening }.takeIf { it >= 0 } ?: 0)
            .map { it.toResponse() }
    }

    /**
     * Opens a team-mode visit, streaming a greeting grounded in the team's attention list.
     *
     * Follows [BuddyService.streamOpenForMe] rule for rule: an existing greeting is replayed whole, a
     * stream that broke keeps what was read, one that produced nothing persists nothing.
     *
     * @throws ResponseStatusException 404 if the user does not exist; 403 if they do not manage the project.
     */
    suspend fun streamOpenForMe(authId: String, projectId: UUID): Flow<BuddyStreamEvent> {
        val userId = authorize(authId, projectId)
        val session = getOrCreateSession(userId, projectId)
        val all = buddyTeamMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id)

        val greetingAlreadyThere = all.lastOrNull()?.takeIf { it.opening }
        if (greetingAlreadyThere != null) {
            return flowOf(
                BuddyStreamEvent(type = BuddyService.TOKEN, content = greetingAlreadyThere.content),
                BuddyStreamEvent(type = BuddyService.DONE),
            )
        }

        val recent = all.drop(session.summarizedCount).map { it.toAgentMessage() }
        val state = buddyTeamTools.teamSnapshot(projectId)

        return flow {
            val streamed = StringBuilder()
            val opening = relayOpening(
                BuddyOpenRequest(memory = session.summary, recent = recent, state = state, teamMode = true),
                streamed,
            )
            finishOpen(session, streamed.toString(), opening)
            compactInBackground(userId, projectId)
        }
    }

    /**
     * Forwards the greeting's words to the manager as they are written.
     *
     * Opening the buddy must never fail the page, so a broken stream ends the read early and keeps what
     * [streamed] already holds.
     *
     * @return The AI's closing event, or `null` when the stream broke or never finished.
     */
    private suspend fun FlowCollector<BuddyStreamEvent>.relayOpening(
        request: BuddyOpenRequest,
        streamed: StringBuilder,
    ): BuddyOpenStreamEvent? {
        var opening: BuddyOpenStreamEvent? = null
        try {
            onboardingAiClient.streamBuddyOpen(request).collect { event ->
                when (event.type) {
                    BuddyOpenStreamEvent.TOKEN -> event.content?.let {
                        streamed.append(it)
                        emit(BuddyStreamEvent(type = BuddyService.TOKEN, content = it))
                    }

                    BuddyOpenStreamEvent.DONE -> opening = event
                }
            }
        } catch (@Suppress("SwallowedException") e: OnboardingAiException) {
            logger.warn("Team buddy open stream failed: {}", e.message)
        }
        return opening
    }

    /**
     * Sends the manager's message in team mode and streams the reply.
     *
     * With [capabilitiesEnabled] false no tools are mounted, exactly as for the hire's buddy. The [filters]
     * narrow retrieval by source system and time range; they are sent on every hop, because the AI service
     * applies them on whichever hop the model chooses to search.
     *
     * @throws ResponseStatusException 404 if the user does not exist; 403 if they do not manage the project.
     */
    suspend fun sendMessageForMe(
        authId: String,
        projectId: UUID,
        content: String,
        capabilitiesEnabled: Boolean = true,
        filters: BuddySessionFilters? = null,
        currentPage: String? = null,
    ): Flow<BuddyStreamEvent> {
        val userId = authorize(authId, projectId)
        val session = getOrCreateSession(userId, projectId)

        // Read before saving the new message so it is not sent to the AI twice.
        val transcript = buddyTeamMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id)
        val history = transcript.drop(session.summarizedCount).map { it.toAgentMessage() }
        // What the latest replies opened or used, from the whole transcript rather than the unfolded part: a
        // fold must not close an area the manager is still working in.
        val carriedOver = transcript.areasStillOpen()

        buddyTeamMessageRepository.save(
            BuddyTeamMessage(session = session, role = BuddyMessageRole.USER, content = content),
        )

        return flow {
            val turn = AgentTurn(
                context = TeamToolContext(
                    userId = userId,
                    authId = authId,
                    projectId = projectId,
                    currentPage = currentPage,
                ),
                capabilitiesEnabled = capabilitiesEnabled,
                filters = filters,
                priorSummary = session.summary,
                messages = history + BuddyAgentMessageDto(role = BuddyAgentMessageDto.ROLE_USER, content = content),
                areas = OpenAreas(carriedOver),
            )

            // The whole loop is guarded, not only the last emission: the words now leave while the
            // loop runs, so a stop or a failure at any hop leaves a partial reply worth keeping —
            // the same guard, and the same reason, as the hire's buddy has. A cancellation is caught
            // here too, and rethrown.
            try {
                while (!turn.isFinished) {
                    runHop(turn)
                }
                completeReply(turn.answer, turn.emittedContent)
                emitCitationsAndDone(resolveCitations(turn.citations))
            } catch (e: Exception) {
                saveIncompleteReply(session, turn.emittedContent.toString(), turn.areas)
                throw e
            }

            buddyTeamMessageRepository.save(
                BuddyTeamMessage(
                    session = session,
                    role = BuddyMessageRole.ASSISTANT,
                    content = turn.emittedContent.toString(),
                    // What this reply opened or used; the next message reads the latest replies' back.
                    openedAreas = turn.areas.activeThisTurn.encoded(),
                ),
            )
            compactInBackground(userId, projectId)
        }.endFailureWithErrorEvent()
    }

    /**
     * Runs one hop of the agent loop: asks the AI for a step, then either takes its answer, sends a
     * written-out tool call back, or runs the tools it asked for.
     */
    private suspend fun FlowCollector<BuddyStreamEvent>.runHop(turn: AgentTurn) {
        turn.step++
        // Per hop, not per turn: an area opened on the previous hop is mounted from this one, and
        // one the latest replies opened or used is mounted from the first.
        val tools = if (turn.capabilitiesEnabled) buddyTeamTools.toolSpecs(turn.areas.mounted) else emptyList()
        val response = relayAgentTurn(
            onboardingAiClient.buddyAgentTurnStream(agentRequest(turn, tools)),
            turn.emittedContent,
        )
        turn.citations = response.citations

        when {
            response.final && response.text.writesOutAToolCall() -> askAgainAfterWrittenOutCall(turn, response, tools)
            response.final -> turn.answer = response.text
            else -> turn.messages = answerToolCalls(turn, response, tools)
        }
    }

    private fun agentRequest(turn: AgentTurn, tools: List<BuddyToolSpecDto>): BuddyAgentRequest =
        BuddyAgentRequest(
            messages = turn.messages,
            backendTools = tools,
            priorSummary = if (turn.step == 1) turn.priorSummary else null,
            vocabulary = VOCABULARY,
            // Retrieval is scoped to the one project this conversation is about.
            projectIds = listOf(turn.context.projectId.toString()),
            capabilitiesEnabled = turn.capabilitiesEnabled,
            teamMode = true,
            filters = turn.filters,
        )

    /**
     * Handles a reply that was a tool call written out as text, which is not an answer: shown, it would
     * read as a reply to the manager. It is sent back once per hop, and if the budget runs out the
     * reply is the fallback, never the raw call.
     *
     * The words were already streamed while the model wrote them, so the client is told to take back
     * what it has shown, and the stored reply starts over with the next ask.
     */
    private suspend fun FlowCollector<BuddyStreamEvent>.askAgainAfterWrittenOutCall(
        turn: AgentTurn,
        response: BuddyAgentResponse,
        tools: List<BuddyToolSpecDto>,
    ) {
        logger.warn("Team buddy wrote a tool call out as its reply; asking again")
        emit(BuddyStreamEvent(type = BuddyService.RESET))
        turn.emittedContent.setLength(0)
        val retry = openAreasWrittenOut(response.text, tools.map { it.name }.toSet(), turn.areas)
        turn.messages = response.messages.ifEmpty {
            turn.messages + BuddyAgentMessageDto(role = BuddyAgentMessageDto.ROLE_ASSISTANT, content = response.text)
        } + BuddyAgentMessageDto(role = BuddyAgentMessageDto.ROLE_USER, content = retry)
    }

    /** Runs every tool the AI asked for on this hop, and returns the conversation with their results. */
    private suspend fun FlowCollector<BuddyStreamEvent>.answerToolCalls(
        turn: AgentTurn,
        response: BuddyAgentResponse,
        tools: List<BuddyToolSpecDto>,
    ): List<BuddyAgentMessageDto> {
        val mounted = tools.map { it.name }.toSet()
        val results = response.pendingToolCalls.map { call ->
            BuddyAgentMessageDto(
                role = BuddyAgentMessageDto.ROLE_TOOL,
                content = runToolCall(call, turn.context, mounted, turn.areas),
                toolCallId = call.id,
            )
        }
        return response.messages + results
    }

    /** The sources the reply drew on that still resolve to an artifact; the rest are logged and dropped. */
    private fun resolveCitations(citations: List<BuddyCitationDto>): List<ResolvedBuddyCitation> =
        citations.mapNotNull { citation ->
            val artifactId = citation.artifactId?.let(::parseUuidOrNull)
            val resolved = artifactId?.let(artifactLookupService::resolve)

            if (artifactId == null || resolved == null) {
                logger.warn("Could not resolve artifact {} for buddy citation", citation.artifactId)
                null
            } else {
                ResolvedBuddyCitation(
                    artifactId = artifactId,
                    filename = resolved.filename,
                    sourceUrl = resolved.sourceUrl,
                    startLine = citation.startLine,
                    startPage = citation.startPage,
                )
            }
        }

    /**
     * Keeps a turn that was cut short — stopped or failed mid-answer — as an incomplete reply, the
     * same way the hire's buddy does, so the manager's read shows what they saw. The areas the turn
     * had opened are stored with it, exactly as a finished reply's are: the tools it used stay
     * mounted for the reply that comes next.
     */
    private fun saveIncompleteReply(session: BuddyTeamSession, content: String, areas: OpenAreas) {
        if (content.isBlank()) {
            return
        }

        buddyTeamMessageRepository.save(
            BuddyTeamMessage(
                session = session,
                role = BuddyMessageRole.ASSISTANT,
                content = content,
                openedAreas = areas.activeThisTurn.encoded(),
                isIncomplete = true,
            ),
        )
    }

    /**
     * Runs one tool the AI asked for in team mode.
     *
     * A mounted action never runs here: it becomes a stored proposal and an `action_proposal` event the
     * manager confirms or dismisses, and the model is told it was offered, not done. `open_area` changes
     * what later hops mount. Every other tool is executed by [BuddyTeamTools], which refuses anything not
     * mounted on this hop — an action name that was not mounted ends up there too, and is refused.
     */
    private suspend fun FlowCollector<BuddyStreamEvent>.runToolCall(
        call: BuddyToolCallDto,
        context: TeamToolContext,
        mountedToolNames: Set<String>,
        areas: OpenAreas,
    ): String {
        openAreaOf(call, mountedToolNames, areas)?.let { return it }
        if (call.name in mountedToolNames) {
            buddyTeamTools.areaOf(call.name)?.let { areas.use(it) }
        }
        if (call.name in mountedToolNames && buddyProposalService.isAction(call.name)) {
            val outcome = buddyProposalService.propose(call, context)
            outcome.proposal?.let { proposal ->
                emit(
                    BuddyStreamEvent(
                        type = BuddyService.ACTION_PROPOSAL,
                        action = proposal.action,
                        label = proposal.label,
                        proposalId = proposal.id.toString(),
                        preview = proposal.preview,
                        risk = proposal.risk.name,
                    ),
                )
            }
            return outcome.toolResult
        }
        emit(toolUseEvent(call.name))
        if (call.name == BuddyTeamTools.OPEN_AREA && call.name in mountedToolNames) {
            val outcome = buddyTeamTools.openArea(call)
            outcome.area?.let { areas.open(it) }
            return outcome.toolResult
        }
        return buddyTeamTools.execute(call, context, mountedToolNames)
    }

    /**
     * Opens the area of a tool the model called before it was mounted, and asks it to call again.
     *
     * The model is told to open an area first, but that is an instruction, not a guarantee: a draft written
     * without opening the knowledge area leaves "yes, send it" with nothing to call. The backend knows which
     * area every tool belongs to, so it opens it itself. The call is not run on this hop: its arguments
     * were written without the tool's description, and nothing runs that was not mounted for the hop it was
     * called on. Only when `open_area` is mounted, so never with capabilities off.
     *
     * @return What to tell the model, or `null` when the call is not for an unopened area.
     */
    private suspend fun FlowCollector<BuddyStreamEvent>.openAreaOf(
        call: BuddyToolCallDto,
        mountedToolNames: Set<String>,
        areas: OpenAreas,
    ): String? {
        if (call.name in mountedToolNames || BuddyTeamTools.OPEN_AREA !in mountedToolNames) {
            return null
        }
        val area = buddyTeamTools.areaOf(call.name) ?: return null
        logger.info("Team buddy called {} before opening its area; opening {}", call.name, area)
        emit(toolUseEvent(BuddyTeamTools.OPEN_AREA))
        areas.open(area)
        return "${call.name} belongs to the ${area.name.lowercase()} area, which was not open. It is open now: " +
            "call ${call.name} again on your next step. Nothing has been done or offered yet."
    }

    /**
     * Opens the areas of tools the model wrote out as text instead of calling, and says what to do instead.
     *
     * Writing a call out is the small model's usual way of failing to make one, and the tool it names is often
     * in an area that is not open, the same case [openAreaOf] handles for a call that was made. Without this,
     * the model is only told to open the area itself, which costs a step of a short budget.
     *
     * @return What to tell the model in place of the reply it wrote.
     */
    private suspend fun FlowCollector<BuddyStreamEvent>.openAreasWrittenOut(
        text: String,
        mountedToolNames: Set<String>,
        areas: OpenAreas,
    ): String {
        if (BuddyTeamTools.OPEN_AREA !in mountedToolNames) {
            return TOOL_CALL_WRITTEN_OUT
        }
        val opened = text
            .writtenOutToolNames()
            .filter { it !in mountedToolNames }
            .mapNotNull { name -> buddyTeamTools.areaOf(name)?.let { name to it } }
        if (opened.isEmpty()) {
            return TOOL_CALL_WRITTEN_OUT
        }
        emit(toolUseEvent(BuddyTeamTools.OPEN_AREA))
        opened.forEach { (_, area) -> areas.open(area) }
        val names = opened.joinToString(", ") { it.first }
        return "That reply was a tool call written out as text, so nothing ran and the manager has not seen an " +
            "answer. The area of $names is open now: call it properly on your next step, not as text."
    }

    private suspend fun FlowCollector<BuddyStreamEvent>.finishOpen(
        session: BuddyTeamSession,
        streamed: String,
        opening: BuddyOpenStreamEvent?,
    ) {
        val greeting = opening?.greeting?.takeIf { it.isNotBlank() } ?: streamed

        if (greeting.isBlank()) {
            // Nothing reached the manager, so nothing is persisted: a stored fallback would become
            // this visit's permanent greeting.
            emit(BuddyStreamEvent(type = BuddyService.TOKEN, content = FALLBACK_TEAM_OPENING))
            emit(BuddyStreamEvent(type = BuddyService.DONE))
            return
        }

        buddyTeamMessageRepository.save(
            BuddyTeamMessage(
                session = session,
                role = BuddyMessageRole.ASSISTANT,
                content = greeting,
                opening = true,
            ),
        )

        opening?.action?.let {
            emit(BuddyStreamEvent(type = BuddyService.OPENING_ACTION, label = it.label, question = it.question))
        }
        emit(BuddyStreamEvent(type = BuddyService.DONE))
    }

    private fun getOrCreateSession(userId: UUID, projectId: UUID): BuddyTeamSession =
        buddyTeamSessionRepository.findByUserIdAndProjectId(userId, projectId)
            ?: buddyTeamSessionRepository.save(BuddyTeamSession(userId = userId, projectId = projectId))

    /** Fire-and-forget, for the same reason as [BuddyService]: nobody may wait on a fold. */
    private fun compactInBackground(userId: UUID, projectId: UUID) {
        applicationScope.launch {
            buddyCompactionService.compactTeamIfNeeded(userId, projectId)
        }
    }

    /**
     * Resolves the caller and confirms they manage [projectId].
     *
     * @throws ResponseStatusException 404 if the user does not exist; 403 if they do not manage the project.
     */
    private fun authorize(authId: String, projectId: UUID): UUID {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }
        if (!userApi.canManageProject(authId, projectId)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Team mode is only available for a project you manage, and you do not manage project $projectId.",
            )
        }
        return userId
    }

    /** What one manager message carries from hop to hop of the agent loop. */
    private class AgentTurn(
        val context: TeamToolContext,
        val capabilitiesEnabled: Boolean,
        val filters: BuddySessionFilters?,
        val priorSummary: String?,
        var messages: List<BuddyAgentMessageDto>,
        val areas: OpenAreas,
    ) {
        var citations: List<BuddyCitationDto> = emptyList()

        /** Everything the manager has been shown of the reply, across every hop of the loop. */
        val emittedContent = StringBuilder()
        var answer: String? = null
        var step = 0

        val isFinished: Boolean
            get() = answer != null || step >= BuddyService.MAX_AGENT_STEPS
    }

    private companion object {
        val VOCABULARY = BuddyVocabularyDto(
            contributionNoun = ContributionWording.NOUN,
            contributionNounPlural = ContributionWording.NOUN_PLURAL,
            contributionVerbPast = ContributionWording.VERB_PAST,
        )

        const val FALLBACK_TEAM_OPENING =
            "You're in team mode. Ask me who needs your attention, or how somebody on the team is getting on."
    }
}
