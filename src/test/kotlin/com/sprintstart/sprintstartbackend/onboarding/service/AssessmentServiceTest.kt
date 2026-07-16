package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.SkillAssessmentSessionStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentResultSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnResponse
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.SkillAssessmentSession
import com.sprintstart.sprintstartbackend.onboarding.model.entity.SkillAssessmentTurn
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.SkillAssessmentSessionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssessmentServiceTest {
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val skillAssessmentSessionRepository: SkillAssessmentSessionRepository = mockk()
    private val competencyRepository: CompetencyRepository = mockk()
    private val userCompetencyStateRepository: UserCompetencyStateRepository = mockk()
    private val userApi: UserApi = mockk()
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service =
        AssessmentService(
            onboardingAiClient,
            skillAssessmentSessionRepository,
            competencyRepository,
            userCompetencyStateRepository,
            userApi,
            transactionManager,
        )

    private val userId = UUID.randomUUID()
    private val authId = "auth|test-user"
    private val kotlinCompetency = Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)

    private fun setUpUser() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
    }

    @Nested
    inner class StartAssessment {
        @Test
        fun `creates a new session and first turn when none is in progress`() = runTest {
            setUpUser()
            every {
                skillAssessmentSessionRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                    userId,
                    SkillAssessmentSessionStatus.IN_PROGRESS,
                )
            } returns null
            every { competencyRepository.findAllByKind(CompetencyKind.SKILL) } returns listOf(kotlinCompetency)
            coEvery { onboardingAiClient.assessTurn(any()) } returns
                AssessmentTurnResponse(done = false, question = "Walk me through a Kotlin null-safety pitfall.")
            val savedSlot = slot<SkillAssessmentSession>()
            every { skillAssessmentSessionRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            val result = service.startAssessment(authId)

            assertEquals("Walk me through a Kotlin null-safety pitfall.", result.question)
            assertEquals(savedSlot.captured.id, result.sessionId)
            assertEquals(1, savedSlot.captured.turns.size)
            assertEquals(0, savedSlot.captured.turns[0].turnIndex)
            assertNull(savedSlot.captured.turns[0].answer)
        }

        @Test
        fun `resumes an existing in-progress session instead of creating a duplicate`() = runTest {
            setUpUser()
            val existing = SkillAssessmentSession(userId = userId)
            existing.turns.add(SkillAssessmentTurn(session = existing, turnIndex = 0, question = "First question?"))
            every {
                skillAssessmentSessionRepository.findFirstByUserIdAndStatusOrderByCreatedAtDesc(
                    userId,
                    SkillAssessmentSessionStatus.IN_PROGRESS,
                )
            } returns existing

            val result = service.startAssessment(authId)

            assertEquals(existing.id, result.sessionId)
            assertEquals("First question?", result.question)
            coVerify(exactly = 0) { onboardingAiClient.assessTurn(any()) }
            verify(exactly = 0) { skillAssessmentSessionRepository.save(any()) }
        }
    }

    @Nested
    inner class AnswerAssessment {
        private fun sessionWithOpenTurn(turnIndex: Int = 0, question: String = "Q$turnIndex"): SkillAssessmentSession {
            val session = SkillAssessmentSession(userId = userId)
            session.turns.add(SkillAssessmentTurn(session = session, turnIndex = turnIndex, question = question))
            return session
        }

        @Test
        fun `persists the answer and advances to the next question when not done`() = runTest {
            setUpUser()
            val session = sessionWithOpenTurn()
            every { skillAssessmentSessionRepository.findById(session.id) } returns Optional.of(session)
            every { competencyRepository.findAllByKind(CompetencyKind.SKILL) } returns listOf(kotlinCompetency)
            coEvery { onboardingAiClient.assessTurn(any()) } returns
                AssessmentTurnResponse(done = false, question = "Second question?")

            val result = service.answerAssessment(authId, session.id, "my answer")

            assertEquals(false, result.done)
            assertEquals("Second question?", result.question)
            assertEquals(2, session.turns.size)
            assertEquals("my answer", session.turns[0].answer)
            assertEquals("Second question?", session.turns[1].question)
            assertNull(session.turns[1].answer)
        }

        @Test
        fun `writes UserCompetencyState and completes the session when done`() = runTest {
            setUpUser()
            val session = sessionWithOpenTurn()
            every { skillAssessmentSessionRepository.findById(session.id) } returns Optional.of(session)
            every { competencyRepository.findAllByKind(CompetencyKind.SKILL) } returns listOf(kotlinCompetency)
            coEvery { onboardingAiClient.assessTurn(any()) } returns
                AssessmentTurnResponse(
                    done = true,
                    assessments = listOf(
                        AssessmentResultSchema(
                            key = "kotlin",
                            level = "advanced",
                            confidence = 0.8,
                            evidence = "Discussed null-safety tradeoffs unprompted.",
                        ),
                    ),
                )
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, "kotlin") } returns null
            val savedSlot = slot<UserCompetencyState>()
            every { userCompetencyStateRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            val result = service.answerAssessment(authId, session.id, "my answer")

            assertTrue(result.done)
            assertNull(result.question)
            assertEquals(SkillAssessmentSessionStatus.COMPLETED, session.status)
            assertEquals("kotlin", savedSlot.captured.competencyKey)
            assertEquals(3, savedSlot.captured.level)
            assertEquals(CompetencySource.ASSESSED, savedSlot.captured.source)
        }

        @Test
        fun `drops an assessment key outside the candidate set`() = runTest {
            setUpUser()
            val session = sessionWithOpenTurn()
            every { skillAssessmentSessionRepository.findById(session.id) } returns Optional.of(session)
            every { competencyRepository.findAllByKind(CompetencyKind.SKILL) } returns listOf(kotlinCompetency)
            coEvery { onboardingAiClient.assessTurn(any()) } returns
                AssessmentTurnResponse(
                    done = true,
                    assessments = listOf(
                        AssessmentResultSchema(key = "kotlin", level = "advanced", confidence = 0.8),
                        AssessmentResultSchema(key = "rust", level = "expert", confidence = 0.9),
                    ),
                )
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, "kotlin") } returns null
            every { userCompetencyStateRepository.save(any()) } answers { firstArg() }

            service.answerAssessment(authId, session.id, "my answer")

            verify(exactly = 1) { userCompetencyStateRepository.save(any()) }
        }

        @Test
        fun `sets must_finish on the final allowed turn`() = runTest {
            setUpUser()
            // MAX_TURNS = 6 (indices 0..5); turns 0..3 answered, turn 4 open -> nextTurnIndex = 5,
            // the last allowed index, so must_finish should flip to true.
            val session = SkillAssessmentSession(userId = userId)
            repeat(4) { i ->
                session.turns.add(
                    SkillAssessmentTurn(session = session, turnIndex = i, question = "Q$i", answer = "A$i"),
                )
            }
            session.turns.add(SkillAssessmentTurn(session = session, turnIndex = 4, question = "Q4"))
            every { skillAssessmentSessionRepository.findById(session.id) } returns Optional.of(session)
            every { competencyRepository.findAllByKind(CompetencyKind.SKILL) } returns listOf(kotlinCompetency)
            val requestSlot = slot<AssessmentTurnRequest>()
            coEvery { onboardingAiClient.assessTurn(capture(requestSlot)) } returns
                AssessmentTurnResponse(done = true, assessments = emptyList())
            every { userCompetencyStateRepository.findByUserIdAndCompetencyKey(any(), any()) } returns null

            service.answerAssessment(authId, session.id, "final answer")

            assertEquals(5, requestSlot.captured.turn)
            assertTrue(requestSlot.captured.mustFinish)
        }

        @Test
        fun `throws 404 for a session that does not exist`() {
            setUpUser()
            val sessionId = UUID.randomUUID()
            every { skillAssessmentSessionRepository.findById(sessionId) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> {
                runBlocking { service.answerAssessment(authId, sessionId, "answer") }
            }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }

        @Test
        fun `throws 404 for a session owned by a different user`() {
            setUpUser()
            val otherUsersSession = SkillAssessmentSession(userId = UUID.randomUUID())
            otherUsersSession.turns.add(
                SkillAssessmentTurn(session = otherUsersSession, turnIndex = 0, question = "Q0"),
            )
            every { skillAssessmentSessionRepository.findById(otherUsersSession.id) } returns
                Optional.of(otherUsersSession)

            val ex = assertThrows<ResponseStatusException> {
                runBlocking { service.answerAssessment(authId, otherUsersSession.id, "answer") }
            }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }

        @Test
        fun `throws 409 when the session has no open turn`() {
            setUpUser()
            val session = SkillAssessmentSession(userId = userId)
            session.turns.add(
                SkillAssessmentTurn(session = session, turnIndex = 0, question = "Q0", answer = "already answered"),
            )
            every { skillAssessmentSessionRepository.findById(session.id) } returns Optional.of(session)

            val ex = assertThrows<ResponseStatusException> {
                runBlocking { service.answerAssessment(authId, session.id, "answer") }
            }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }
    }
}
