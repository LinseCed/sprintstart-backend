package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BuddyMessageRole
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentMessageDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyCitationDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyStreamEvent
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyMessage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddySession
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.buddy.BuddyMessageResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddySessionRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Manages a hire's ongoing onboarding buddy conversation: one continuous [BuddySession] per user,
 * durable across visits, backed by the stateless AI buddy-agent endpoint.
 *
 * The buddy is a tool-using agent. This service runs the agent loop: it asks the AI to reason over
 * the conversation (with the backend tools it may call), executes any tool the AI hands back —
 * strictly on behalf of the resolved caller — feeds each result in, and repeats until the AI has a
 * final answer. The AI stays stateless; the running [BuddyAgentMessageDto] list lives here for the
 * length of one reply. Corpus questions are answered AI-side via ``search_docs``; questions about
 * the hire's own onboarding are answered by [BuddyToolExecutor].
 */
@Service
class BuddyService(
    private val buddySessionRepository: BuddySessionRepository,
    private val buddyMessageRepository: BuddyMessageRepository,
    private val onboardingAiClient: OnboardingAiClient,
    private val buddyToolExecutor: BuddyToolExecutor,
    private val buddyActionService: BuddyActionService,
    private val userApi: UserApi,
) {
    /** Finds or creates a user's one ongoing buddy session. */
    fun getOrCreateSession(userId: UUID): BuddySession =
        buddySessionRepository.findByUserId(userId)
            ?: buddySessionRepository.save(BuddySession(userId = userId))

    /** Returns the authenticated user's buddy conversation so far, oldest first. */
    fun getMessagesForMe(authId: String): List<BuddyMessageResponse> {
        val userId = resolveUserId(authId)
        val session = buddySessionRepository.findByUserId(userId) ?: return emptyList()
        return buddyMessageRepository
            .findAllBySessionIdOrderByCreatedAtAsc(session.id)
            .map { it.toResponse() }
    }

    /**
     * Sends the authenticated user's message to the buddy and streams the reply.
     *
     * The user's message is persisted immediately; the assistant's reply is persisted only once the
     * agent loop finishes, so a stream that errors or is cancelled leaves no garbage reply behind.
     *
     * @throws ResponseStatusException 404 if the authenticated user doesn't exist.
     */
    suspend fun sendMessageForMe(authId: String, content: String): Flow<BuddyStreamEvent> {
        val userId = resolveUserId(authId)
        val session = getOrCreateSession(userId)

        // Read history before saving the new message so it isn't sent to the AI service twice.
        val history = buddyMessageRepository
            .findAllBySessionIdOrderByCreatedAtAsc(session.id)
            .map { BuddyAgentMessageDto(role = it.role.toHistoryRole(), content = it.content) }

        buddyMessageRepository.save(
            BuddyMessage(session = session, role = BuddyMessageRole.USER, content = content),
        )

        // The AI reasoner sees the read-only tools *and* the action tools it may propose. An action
        // tool call never mutates here — it produces a proposal the hire must confirm out-of-band.
        val tools = buddyToolExecutor.toolSpecs() + buddyActionService.actionSpecs()

        return flow {
            var messages = history + BuddyAgentMessageDto(role = "user", content = content)
            var citations: List<BuddyCitationDto> = emptyList()
            var answer: String? = null
            var step = 0

            while (answer == null && step < MAX_AGENT_STEPS) {
                step++
                val response = onboardingAiClient.buddyAgentTurn(
                    BuddyAgentRequest(messages = messages, backendTools = tools),
                )
                citations = response.citations
                if (response.final) {
                    answer = response.text
                } else {
                    // The AI needs a backend tool run: execute each on the caller's behalf and feed
                    // the result back as a `tool` message appended to the running conversation.
                    val next = response.messages.toMutableList()
                    for (call in response.pendingToolCalls) {
                        val result = if (buddyActionService.isAction(call.name)) {
                            // An action tool: propose it to the hire (never mutate). The proposal, if
                            // one can be offered, goes out as its own event the client gates behind a
                            // confirm button; the AI is told it was proposed, not performed.
                            val outcome = buddyActionService.propose(call, userId)
                            outcome.proposal?.let { proposal ->
                                emit(
                                    BuddyStreamEvent(
                                        type = "action_proposal",
                                        action = proposal.action,
                                        label = proposal.label,
                                        question = proposal.question,
                                    ),
                                )
                            }
                            outcome.toolResult
                        } else {
                            emit(BuddyStreamEvent(type = "tool_use", name = call.name, kind = "tool"))
                            buddyToolExecutor.execute(call, userId)
                        }
                        next.add(
                            BuddyAgentMessageDto(role = "tool", content = result, toolCallId = call.id),
                        )
                    }
                    messages = next
                }
            }

            val reply = answer?.takeIf { it.isNotBlank() } ?: FALLBACK_REPLY
            // The agent turn returns the answer whole; emit it in word-sized chunks so the client
            // still renders it progressively. This is paced emission, not true token streaming --
            // streaming the model's tokens through a tool-calling turn is a separate change.
            for (chunk in TOKEN_CHUNK.split(reply).filter { it.isNotEmpty() }) {
                emit(BuddyStreamEvent(type = "token", content = chunk))
            }
            for (citation in citations) {
                emit(
                    BuddyStreamEvent(
                        type = "citation",
                        artifactId = citation.artifactId,
                        startLine = citation.startLine,
                        startPage = citation.startPage,
                    ),
                )
            }
            emit(BuddyStreamEvent(type = "done"))

            buddyMessageRepository.save(
                BuddyMessage(session = session, role = BuddyMessageRole.ASSISTANT, content = reply),
            )
        }
    }

    private fun BuddyMessageRole.toHistoryRole(): String =
        when (this) {
            BuddyMessageRole.USER -> "user"
            BuddyMessageRole.ASSISTANT -> "assistant"
        }

    private fun resolveUserId(authId: String): UUID =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }

    private companion object {
        // How many agent round-trips (AI reason -> backend tool -> AI reason) before we stop and
        // answer with what we have. The AI service has its own internal search budget; this bounds
        // only the backend-tool hops so a loop can never run unbounded.
        const val MAX_AGENT_STEPS = 5
        const val FALLBACK_REPLY =
            "I wasn't able to finish answering that one — could you rephrase or add a little detail?"

        // Split after each space, keeping the space on the preceding chunk, so concatenating every
        // emitted token reproduces the answer exactly (newlines and punctuation preserved).
        val TOKEN_CHUNK = Regex("(?<= )")
    }
}
