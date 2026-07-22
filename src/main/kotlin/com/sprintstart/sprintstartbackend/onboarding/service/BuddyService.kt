package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BuddyMessageRole
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentHistoryEntrySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyStreamEvent
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyMessage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddySession
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.buddy.BuddyMessageResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddySessionRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Manages a hire's ongoing onboarding buddy conversation: one continuous [BuddySession] per user,
 * durable across visits, backed by the stateless AI buddy endpoint.
 *
 * Mirrors [com.sprintstart.sprintstartbackend.chat.service.ChatService.prompt]'s persist-on-
 * stream-completion shape rather than [VerificationService]/[AssessmentService]'s explicit
 * `TransactionTemplate` split -- there's no atomicity invariant across
 * find-session/save-user-message/stream/save-reply the way there is for a ledger write, so no
 * wrapping transaction is needed; each repository call commits on its own.
 */
@Service
class BuddyService(
    private val buddySessionRepository: BuddySessionRepository,
    private val buddyMessageRepository: BuddyMessageRepository,
    private val onboardingAiClient: OnboardingAiClient,
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
     * The user's message is persisted immediately; the assistant's reply is persisted only once
     * the stream completes successfully, so a stream that errors or is cancelled leaves no
     * garbage assistant message behind.
     *
     * @throws ResponseStatusException 404 if the authenticated user doesn't exist.
     */
    suspend fun sendMessageForMe(authId: String, content: String): Flow<BuddyStreamEvent> {
        val userId = resolveUserId(authId)
        val session = getOrCreateSession(userId)

        // Read history before saving the new message so it isn't sent to the AI service twice.
        val history = buddyMessageRepository
            .findAllBySessionIdOrderByCreatedAtAsc(session.id)
            .map { AssessmentHistoryEntrySchema(role = it.role.toHistoryRole(), content = it.content) }

        buddyMessageRepository.save(
            BuddyMessage(session = session, role = BuddyMessageRole.USER, content = content),
        )

        val reply = StringBuilder()
        return onboardingAiClient
            .streamBuddy(question = content, history = history)
            .map { event ->
                if (event.type == "token") event.content?.let(reply::append)
                event
            }.onCompletion { cause ->
                if (cause == null) {
                    buddyMessageRepository.save(
                        BuddyMessage(session = session, role = BuddyMessageRole.ASSISTANT, content = reply.toString()),
                    )
                }
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
}
