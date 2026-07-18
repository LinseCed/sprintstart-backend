package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BuddyMessageRole
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentHistoryEntrySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyStreamEvent
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyMessage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddySession
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddySessionRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class BuddyServiceTest {
    private val buddySessionRepository: BuddySessionRepository = mockk()
    private val buddyMessageRepository: BuddyMessageRepository = mockk()
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val userApi: UserApi = mockk()
    private val service = BuddyService(
        buddySessionRepository,
        buddyMessageRepository,
        onboardingAiClient,
        userApi,
    )

    private val userId = UUID.randomUUID()
    private val authId = "auth|test-user"

    @Nested
    inner class GetOrCreateSession {
        @Test
        fun `returns the existing session when one exists`() {
            val session = BuddySession(userId = userId)
            every { buddySessionRepository.findByUserId(userId) } returns session

            val result = service.getOrCreateSession(userId)

            assertThat(result).isEqualTo(session)
            verify(exactly = 0) { buddySessionRepository.save(any()) }
        }

        @Test
        fun `creates a session when none exists`() {
            every { buddySessionRepository.findByUserId(userId) } returns null
            every { buddySessionRepository.save(any()) } answers { firstArg() }

            val result = service.getOrCreateSession(userId)

            assertThat(result.userId).isEqualTo(userId)
        }
    }

    @Nested
    inner class GetMessagesForMe {
        @Test
        fun `returns an empty list when the user has no session yet`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns null

            val result = service.getMessagesForMe(authId)

            assertThat(result).isEmpty()
        }

        @Test
        fun `returns the session's messages oldest first`() {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns listOf(
                BuddyMessage(session = session, role = BuddyMessageRole.USER, content = "Hi"),
                BuddyMessage(session = session, role = BuddyMessageRole.ASSISTANT, content = "Hello!"),
            )

            val result = service.getMessagesForMe(authId)

            assertThat(result).hasSize(2)
            assertThat(result[0].content).isEqualTo("Hi")
            assertThat(result[1].role).isEqualTo(BuddyMessageRole.ASSISTANT)
        }

        @Test
        fun `throws 404 when the authenticated user does not exist`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.empty()

            assertThrows<ResponseStatusException> {
                service.getMessagesForMe(authId)
            }.also { assertThat(it.statusCode.value()).isEqualTo(404) }
        }
    }

    @Nested
    inner class SendMessageForMe {
        @Test
        fun `persists the user message before calling the AI client`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            val saved = mutableListOf<BuddyMessage>()
            every { buddyMessageRepository.save(capture(saved)) } answers { firstArg() }
            every { onboardingAiClient.streamBuddy(any(), any()) } returns flowOf(BuddyStreamEvent(type = "done"))

            service.sendMessageForMe(authId, "How do I get set up?").toList()

            val userMessage = saved.first { it.role == BuddyMessageRole.USER }
            assertThat(userMessage.content).isEqualTo("How do I get set up?")
        }

        @Test
        fun `threads prior messages as history in role order`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns listOf(
                BuddyMessage(session = session, role = BuddyMessageRole.USER, content = "Hi"),
                BuddyMessage(session = session, role = BuddyMessageRole.ASSISTANT, content = "Hello!"),
            )
            every { buddyMessageRepository.save(any()) } answers { firstArg() }
            val capturedHistory = slot<List<AssessmentHistoryEntrySchema>>()
            every {
                onboardingAiClient.streamBuddy(any(), capture(capturedHistory))
            } returns flowOf(BuddyStreamEvent(type = "done"))

            service.sendMessageForMe(authId, "Can you say more?").toList()

            assertThat(capturedHistory.captured).containsExactly(
                AssessmentHistoryEntrySchema(role = "user", content = "Hi"),
                AssessmentHistoryEntrySchema(role = "assistant", content = "Hello!"),
            )
        }

        @Test
        fun `reuses the same session across repeated messages`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            every { buddyMessageRepository.save(any()) } answers { firstArg() }
            every { onboardingAiClient.streamBuddy(any(), any()) } returns flowOf(BuddyStreamEvent(type = "done"))

            service.sendMessageForMe(authId, "First").toList()
            service.sendMessageForMe(authId, "Second").toList()

            verify(exactly = 0) { buddySessionRepository.save(any()) }
        }

        @Test
        fun `persists the assembled assistant reply only once the stream completes`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            val saved = mutableListOf<BuddyMessage>()
            every { buddyMessageRepository.save(capture(saved)) } answers { firstArg() }
            every { onboardingAiClient.streamBuddy(any(), any()) } returns flowOf(
                BuddyStreamEvent(type = "token", content = "No question "),
                BuddyStreamEvent(type = "token", content = "is too basic."),
                BuddyStreamEvent(type = "done"),
            )

            service.sendMessageForMe(authId, "Hi").toList()

            val assistantMessage = saved.first { it.role == BuddyMessageRole.ASSISTANT }
            assertThat(assistantMessage.content).isEqualTo("No question is too basic.")
        }

        @Test
        fun `does not persist an assistant message when the stream fails`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            val saved = mutableListOf<BuddyMessage>()
            every { buddyMessageRepository.save(capture(saved)) } answers { firstArg() }
            every { onboardingAiClient.streamBuddy(any(), any()) } returns kotlinx.coroutines.flow.flow {
                emit(BuddyStreamEvent(type = "token", content = "Partial"))
                throw OnboardingAiException(502, "", "AI buddy responded with error: boom")
            }

            assertThrows<OnboardingAiException> {
                service.sendMessageForMe(authId, "Hi").toList()
            }

            assertThat(saved.map { it.role }).containsExactly(BuddyMessageRole.USER)
        }
    }
}
