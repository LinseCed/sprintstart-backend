package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BuddyMessageRole
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentMessageDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyAgentResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyStreamEvent
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyMessage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddySession
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddySessionRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val buddyToolExecutor: BuddyToolExecutor = mockk()
    private val userApi: UserApi = mockk()
    private val service = BuddyService(
        buddySessionRepository,
        buddyMessageRepository,
        onboardingAiClient,
        buddyToolExecutor,
        userApi,
    )

    private val userId = UUID.randomUUID()
    private val authId = "auth|test-user"

    private fun finalReply(text: String) = BuddyAgentResponse(final = true, text = text)

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
            every { buddyToolExecutor.toolSpecs() } returns emptyList()
            val saved = mutableListOf<BuddyMessage>()
            every { buddyMessageRepository.save(capture(saved)) } answers { firstArg() }
            coEvery { onboardingAiClient.buddyAgentTurn(any()) } returns finalReply("Set up like so.")

            service.sendMessageForMe(authId, "How do I get set up?").toList()

            val userMessage = saved.first { it.role == BuddyMessageRole.USER }
            assertThat(userMessage.content).isEqualTo("How do I get set up?")
        }

        @Test
        fun `threads prior messages and the new question as the running conversation`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns listOf(
                BuddyMessage(session = session, role = BuddyMessageRole.USER, content = "Hi"),
                BuddyMessage(session = session, role = BuddyMessageRole.ASSISTANT, content = "Hello!"),
            )
            every { buddyMessageRepository.save(any()) } answers { firstArg() }
            every { buddyToolExecutor.toolSpecs() } returns emptyList()
            val requests = mutableListOf<BuddyAgentRequest>()
            coEvery { onboardingAiClient.buddyAgentTurn(capture(requests)) } returns finalReply("More detail.")

            service.sendMessageForMe(authId, "Can you say more?").toList()

            assertThat(requests.first().messages).containsExactly(
                BuddyAgentMessageDto(role = "user", content = "Hi"),
                BuddyAgentMessageDto(role = "assistant", content = "Hello!"),
                BuddyAgentMessageDto(role = "user", content = "Can you say more?"),
            )
        }

        @Test
        fun `reuses the same session across repeated messages`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            every { buddyMessageRepository.save(any()) } answers { firstArg() }
            every { buddyToolExecutor.toolSpecs() } returns emptyList()
            coEvery { onboardingAiClient.buddyAgentTurn(any()) } returns finalReply("ok")

            service.sendMessageForMe(authId, "First").toList()
            service.sendMessageForMe(authId, "Second").toList()

            verify(exactly = 0) { buddySessionRepository.save(any()) }
        }

        @Test
        fun `persists the final answer once the agent loop completes`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            every { buddyToolExecutor.toolSpecs() } returns emptyList()
            val saved = mutableListOf<BuddyMessage>()
            every { buddyMessageRepository.save(capture(saved)) } answers { firstArg() }
            coEvery { onboardingAiClient.buddyAgentTurn(any()) } returns finalReply("No question is too basic.")

            service.sendMessageForMe(authId, "Hi").toList()

            val assistantMessage = saved.first { it.role == BuddyMessageRole.ASSISTANT }
            assertThat(assistantMessage.content).isEqualTo("No question is too basic.")
        }

        @Test
        fun `runs a backend tool the AI asks for and feeds the result back`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            every { buddyMessageRepository.save(any()) } answers { firstArg() }
            every { buddyToolExecutor.toolSpecs() } returns emptyList()

            val toolCall = BuddyToolCallDto(id = "call_0", name = "get_my_metrics")
            val paused = BuddyAgentResponse(
                final = false,
                messages = listOf(
                    BuddyAgentMessageDto(role = "assistant", content = "", toolCalls = listOf(toolCall)),
                ),
                pendingToolCalls = listOf(toolCall),
            )
            val requests = mutableListOf<BuddyAgentRequest>()
            coEvery { onboardingAiClient.buddyAgentTurn(capture(requests)) } returnsMany listOf(
                paused,
                finalReply("Your PR has waited 52 hours — that's on the reviewer."),
            )
            every { buddyToolExecutor.execute(toolCall, userId) } returns "openPullRequestCount=1"

            val events = service.sendMessageForMe(authId, "is my PR stuck?").toList()

            // The tool is executed on the caller's behalf...
            coVerify(exactly = 1) { buddyToolExecutor.execute(toolCall, userId) }
            // ...its result is appended to the conversation carried into the resume call...
            assertThat(requests[1].messages).contains(
                BuddyAgentMessageDto(role = "tool", content = "openPullRequestCount=1", toolCallId = "call_0"),
            )
            // ...the hire sees the tool run, and the final answer streams out in chunks whose
            // concatenation is the whole answer.
            assertThat(events.map { it.type }).contains("tool_use", "token", "done")
            val streamed = events.filter { it.type == "token" }.joinToString("") { it.content ?: "" }
            assertThat(streamed).contains("52 hours")
        }

        @Test
        fun `does not persist an assistant message when the agent turn fails`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            every { buddyToolExecutor.toolSpecs() } returns emptyList()
            val saved = mutableListOf<BuddyMessage>()
            every { buddyMessageRepository.save(capture(saved)) } answers { firstArg() }
            coEvery { onboardingAiClient.buddyAgentTurn(any()) } throws
                OnboardingAiException(502, "", "AI buddy responded with error: boom")

            assertThrows<OnboardingAiException> {
                service.sendMessageForMe(authId, "Hi").toList()
            }

            assertThat(saved.map { it.role }).containsExactly(BuddyMessageRole.USER)
        }

        @Test
        fun `emits a BuddyStreamEvent done terminator`() = runTest {
            val session = BuddySession(userId = userId)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { buddySessionRepository.findByUserId(userId) } returns session
            every { buddyMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(session.id) } returns emptyList()
            every { buddyMessageRepository.save(any()) } answers { firstArg() }
            every { buddyToolExecutor.toolSpecs() } returns emptyList()
            coEvery { onboardingAiClient.buddyAgentTurn(any()) } returns finalReply("done")

            val events: List<BuddyStreamEvent> = service.sendMessageForMe(authId, "Hi").toList()

            assertThat(events.last().type).isEqualTo("done")
        }
    }
}
