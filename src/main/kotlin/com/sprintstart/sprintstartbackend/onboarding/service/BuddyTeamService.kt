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
 * Follows the hire's buddy ([BuddyService]) with three differences:
 * - Every entry point confirms through [UserApi.canManageProject] that the caller manages the project.
 * - Each project has its own conversation, separate from the manager's onboarding memory.
 * - The tool list is rebuilt on every hop, because `open_area` changes what the next hop may call.
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
     * Behaves like [BuddyService.streamOpenForMe]: an existing greeting is replayed, a broken stream
     * keeps what was read, and an empty one persists nothing.
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

    /** Forwards the greeting as it is written; a broken stream keeps what [streamed] holds so far. */
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
     * With [capabilitiesEnabled] false no tools are mounted. The [filters] are sent on every hop, because
     * the AI service applies them on whichever hop the model searches.
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
        // From the whole transcript, not the unfolded part: a fold must not close an area still in use.
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

            // Words are streamed while the loop runs, so a stop or failure at any hop leaves a partial reply
            // worth keeping. This also catches cancellation, which is rethrown.
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
                    openedAreas = turn.areas.activeThisTurn.encoded(),
                ),
            )
            compactInBackground(userId, projectId)
        }.endFailureWithErrorEvent()
    }

    private suspend fun FlowCollector<BuddyStreamEvent>.runHop(turn: AgentTurn) {
        turn.step++
        // Per hop, because an area opened on the previous hop is mounted from this one.
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
            projectIds = listOf(turn.context.projectId.toString()),
            capabilitiesEnabled = turn.capabilitiesEnabled,
            teamMode = true,
            filters = turn.filters,
        )

    /**
     * Treats a tool call written out as text as a failed step, not an answer.
     *
     * Its words were already streamed, so the client is told to take them back and the stored reply starts over.
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

    /** Keeps a turn that was stopped or failed mid-answer, so the manager's read shows what they saw. */
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
     * Runs one tool the AI asked for.
     *
     * An action never runs here: it becomes a stored proposal the manager confirms, and the model is told
     * it was offered, not done. Anything not mounted on this hop is refused by [BuddyTeamTools].
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
     * The call itself is not run: its arguments were written without the tool's description.
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
     * Opens the areas of tools the model wrote out as text, the same case [openAreaOf] handles for a real call.
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
            // Not persisted: a stored fallback would become this visit's permanent greeting.
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

    private fun compactInBackground(userId: UUID, projectId: UUID) {
        applicationScope.launch {
            buddyCompactionService.compactTeamIfNeeded(userId, projectId)
        }
    }

    /** @throws ResponseStatusException 404 if the user does not exist; 403 if they do not manage the project. */
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

    private class AgentTurn(
        val context: TeamToolContext,
        val capabilitiesEnabled: Boolean,
        val filters: BuddySessionFilters?,
        val priorSummary: String?,
        var messages: List<BuddyAgentMessageDto>,
        val areas: OpenAreas,
    ) {
        var citations: List<BuddyCitationDto> = emptyList()

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
