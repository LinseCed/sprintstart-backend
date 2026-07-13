package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CheckQuestionType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPhase
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckAttempt
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckOption
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckQuestion
import com.sprintstart.sprintstartbackend.onboarding.model.request.check.SubmitCheckAnswerRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.check.SubmitPhaseCheckAttemptRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.check.UpdateCheckOptionRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.check.UpdateCheckQuestionRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.check.UpdatePhaseCheckRequest
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPhaseRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.PhaseCheckAttemptRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseCheckServiceTest {
    private val onboardingPhaseRepository: OnboardingPhaseRepository = mockk()
    private val phaseCheckAttemptRepository: PhaseCheckAttemptRepository = mockk()
    private val userApi: UserApi = mockk()
    private val service = PhaseCheckService(onboardingPhaseRepository, phaseCheckAttemptRepository, userApi)

    private val userId = UUID.randomUUID()
    private val phaseId = UUID.randomUUID()
    private val authId = "auth|test-user"

    private fun makePath(vararg phasePositions: Int): OnboardingPath {
        val path = OnboardingPath(userId = userId)
        phasePositions.forEach { position ->
            path.phases += OnboardingPhase(path = path, position = position, title = "P$position", description = "d")
        }
        return path
    }

    /** A phase with one multiple-choice and one short-text question. */
    private fun makePhaseWithCheck(path: OnboardingPath = makePath()): OnboardingPhase {
        val phase = OnboardingPhase(id = phaseId, path = path, position = 0, title = "Setup", description = "d")

        val mcQuestion = PhaseCheckQuestion(
            phase = phase,
            position = 0,
            type = CheckQuestionType.MULTIPLE_CHOICE,
            question = "Which is correct?",
            explanation = "Because.",
        )
        mcQuestion.options += PhaseCheckOption(question = mcQuestion, position = 0, label = "Right", correct = true)
        mcQuestion.options += PhaseCheckOption(question = mcQuestion, position = 1, label = "Wrong", correct = false)

        val textQuestion = PhaseCheckQuestion(
            phase = phase,
            position = 1,
            type = CheckQuestionType.SHORT_TEXT,
            question = "Start command?",
            correctAnswer = "gradlew bootRun",
        )

        phase.checkQuestions += mcQuestion
        phase.checkQuestions += textQuestion
        return phase
    }

    private fun correctOptionId(phase: OnboardingPhase) =
        phase.checkQuestions
            .first { it.type == CheckQuestionType.MULTIPLE_CHOICE }
            .options
            .first { it.correct }
            .id

    private fun mcQuestionId(phase: OnboardingPhase) =
        phase.checkQuestions.first { it.type == CheckQuestionType.MULTIPLE_CHOICE }.id

    private fun textQuestionId(phase: OnboardingPhase) =
        phase.checkQuestions.first { it.type == CheckQuestionType.SHORT_TEXT }.id

    /** A phase whose check consists of [count] multiple-choice questions, each with one correct option. */
    private fun makePhaseWithMcQuestions(count: Int): OnboardingPhase {
        val phase = OnboardingPhase(id = phaseId, path = makePath(), position = 0, title = "Setup", description = "d")
        repeat(count) { index ->
            val question = PhaseCheckQuestion(
                phase = phase,
                position = index,
                type = CheckQuestionType.MULTIPLE_CHOICE,
                question = "q$index",
            )
            question.options += PhaseCheckOption(question = question, position = 0, label = "right", correct = true)
            question.options += PhaseCheckOption(question = question, position = 1, label = "wrong", correct = false)
            phase.checkQuestions += question
        }
        return phase
    }

    /** Answers the first [correctCount] questions correctly and the rest incorrectly. */
    private fun answersFor(phase: OnboardingPhase, correctCount: Int): SubmitPhaseCheckAttemptRequest {
        val answers = phase.checkQuestions.sortedBy { it.position }.mapIndexed { index, question ->
            val option = if (index < correctCount) {
                question.options.first { it.correct }
            } else {
                question.options.first { !it.correct }
            }
            SubmitCheckAnswerRequest(questionId = question.id, selectedOptionIds = listOf(option.id))
        }
        return SubmitPhaseCheckAttemptRequest(answers = answers)
    }

    @Nested
    inner class GetPhaseCheckForMe {
        @Test
        fun `returns questions without exposing correct answers`() {
            val phase = makePhaseWithCheck()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)

            val result = service.getPhaseCheckForMe(authId, phaseId)

            assertEquals(2, result.questions.size)
            assertTrue(result.required)
            // The user-facing options DTO has no `correct` field at all; assert both options are returned.
            assertEquals(
                2,
                result.questions
                    .first { it.options.isNotEmpty() }
                    .options.size,
            )
        }

        @Test
        fun `throws 404 when user not found`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.empty()

            assertThrows<ResponseStatusException> {
                service.getPhaseCheckForMe(authId, phaseId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }

        @Test
        fun `throws 404 when phase not found for user`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.empty()

            assertThrows<ResponseStatusException> {
                service.getPhaseCheckForMe(authId, phaseId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }
    }

    @Nested
    inner class SubmitPhaseCheckAttemptForMe {
        @Test
        fun `passes when every answer is correct and reveals correct answers`() {
            val phase = makePhaseWithCheck()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)
            val saved = slot<PhaseCheckAttempt>()
            every { phaseCheckAttemptRepository.save(capture(saved)) } answers { saved.captured }

            val request = SubmitPhaseCheckAttemptRequest(
                answers = listOf(
                    SubmitCheckAnswerRequest(
                        questionId = mcQuestionId(phase),
                        selectedOptionIds = listOf(correctOptionId(phase)),
                    ),
                    SubmitCheckAnswerRequest(
                        questionId = textQuestionId(phase),
                        textAnswer = "gradlew bootRun",
                    ),
                ),
            )

            val result = service.submitPhaseCheckAttemptForMe(authId, phaseId, request)

            assertTrue(result.passed)
            assertTrue(result.results.all { it.correct })
            // Correct answers are only revealed here, in the submit result.
            val mcResult = result.results.first { it.correctOptionIds.isNotEmpty() }
            assertEquals(listOf(correctOptionId(phase)), mcResult.correctOptionIds)
            assertTrue(saved.captured.passed)
        }

        @Test
        fun `short text grading is case insensitive and trimmed`() {
            val phase = makePhaseWithCheck()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)
            every { phaseCheckAttemptRepository.save(any()) } answers { firstArg() }

            val request = SubmitPhaseCheckAttemptRequest(
                answers = listOf(
                    SubmitCheckAnswerRequest(mcQuestionId(phase), selectedOptionIds = listOf(correctOptionId(phase))),
                    SubmitCheckAnswerRequest(textQuestionId(phase), textAnswer = "  GRADLEW BOOTRUN  "),
                ),
            )

            val result = service.submitPhaseCheckAttemptForMe(authId, phaseId, request)

            assertTrue(result.results.first { it.questionId == textQuestionId(phase) }.correct)
        }

        @Test
        fun `fails when an answer is wrong and does not pass the check`() {
            val phase = makePhaseWithCheck()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)
            every { phaseCheckAttemptRepository.save(any()) } answers { firstArg() }

            val request = SubmitPhaseCheckAttemptRequest(
                answers = listOf(
                    SubmitCheckAnswerRequest(mcQuestionId(phase), selectedOptionIds = listOf(correctOptionId(phase))),
                    SubmitCheckAnswerRequest(textQuestionId(phase), textAnswer = "wrong"),
                ),
            )

            val result = service.submitPhaseCheckAttemptForMe(authId, phaseId, request)

            assertFalse(result.passed)
            assertFalse(result.nextPhaseUnlocked)
            val textResult = result.results.first { it.questionId == textQuestionId(phase) }
            assertFalse(textResult.correct)
            assertEquals("gradlew bootRun", textResult.correctAnswer)
        }

        @Test
        fun `a missing answer counts as incorrect`() {
            val phase = makePhaseWithCheck()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)
            every { phaseCheckAttemptRepository.save(any()) } answers { firstArg() }

            // Only answers the MC question, leaves the text question unanswered.
            val request = SubmitPhaseCheckAttemptRequest(
                answers = listOf(
                    SubmitCheckAnswerRequest(mcQuestionId(phase), selectedOptionIds = listOf(correctOptionId(phase))),
                ),
            )

            val result = service.submitPhaseCheckAttemptForMe(authId, phaseId, request)

            assertFalse(result.passed)
        }

        @Test
        fun `unlocks the next phase when passed and steps complete`() {
            // Phase 0 (under test) has no steps -> steps count as complete; phase 1 exists as "next".
            val path = makePath(0, 1)
            val phase = path.phases.first { it.position == 0 }
            val mcQuestion = PhaseCheckQuestion(
                phase = phase,
                position = 0,
                type = CheckQuestionType.MULTIPLE_CHOICE,
                question = "q",
            )
            val correct = PhaseCheckOption(question = mcQuestion, position = 0, label = "ok", correct = true)
            mcQuestion.options += correct
            mcQuestion.options += PhaseCheckOption(question = mcQuestion, position = 1, label = "no", correct = false)
            phase.checkQuestions += mcQuestion

            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phase.id, userId) } returns Optional.of(phase)
            every { phaseCheckAttemptRepository.save(any()) } answers { firstArg() }

            val request = SubmitPhaseCheckAttemptRequest(
                answers = listOf(SubmitCheckAnswerRequest(mcQuestion.id, selectedOptionIds = listOf(correct.id))),
            )

            val result = service.submitPhaseCheckAttemptForMe(authId, phase.id, request)

            assertTrue(result.passed)
            assertTrue(result.nextPhaseUnlocked)
        }

        @Test
        fun `passes when at least 80 percent of the questions are correct`() {
            val phase = makePhaseWithMcQuestions(count = 5)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)
            every { phaseCheckAttemptRepository.save(any()) } answers { firstArg() }

            // 4 of 5 correct = 80%.
            val result = service.submitPhaseCheckAttemptForMe(authId, phaseId, answersFor(phase, correctCount = 4))

            assertTrue(result.passed)
            assertEquals(4, result.correctCount)
            assertEquals(5, result.questionCount)
            assertEquals(80, result.requiredPercent)
        }

        @Test
        fun `fails when fewer than 80 percent of the questions are correct`() {
            val phase = makePhaseWithMcQuestions(count = 5)
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)
            every { phaseCheckAttemptRepository.save(any()) } answers { firstArg() }

            // 3 of 5 correct = 60%.
            val result = service.submitPhaseCheckAttemptForMe(authId, phaseId, answersFor(phase, correctCount = 3))

            assertFalse(result.passed)
            assertEquals(3, result.correctCount)
        }

        @Test
        fun `throws 404 when the phase has no knowledge check`() {
            val phase = OnboardingPhase(id = phaseId, path = makePath(), position = 0, title = "t", description = "d")
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)

            assertThrows<ResponseStatusException> {
                service.submitPhaseCheckAttemptForMe(authId, phaseId, SubmitPhaseCheckAttemptRequest())
            }.also { assertEquals(404, it.statusCode.value()) }
        }
    }

    @Nested
    inner class GetPhaseCheck {
        @Test
        fun `returns questions including correct answers for admins`() {
            val phase = makePhaseWithCheck()
            every { onboardingPhaseRepository.findById(phaseId) } returns Optional.of(phase)

            val result = service.getPhaseCheck(phaseId)

            val mc = result.questions.first { it.options.isNotEmpty() }
            assertTrue(mc.options.any { it.correct })
            assertEquals("gradlew bootRun", result.questions.first { it.correctAnswer != null }.correctAnswer)
        }

        @Test
        fun `throws 404 when phase not found`() {
            every { onboardingPhaseRepository.findById(phaseId) } returns Optional.empty()

            assertThrows<ResponseStatusException> {
                service.getPhaseCheck(phaseId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }
    }

    @Nested
    inner class ReplacePhaseCheck {
        @Test
        fun `replaces questions and returns stored check`() {
            val phase = makePhaseWithCheck()
            every { onboardingPhaseRepository.findById(phaseId) } returns Optional.of(phase)
            every { onboardingPhaseRepository.save(any()) } answers { firstArg() }

            val request = UpdatePhaseCheckRequest(
                questions = listOf(
                    UpdateCheckQuestionRequest(
                        position = 0,
                        type = CheckQuestionType.SHORT_TEXT,
                        question = "New?",
                        correctAnswer = "yes",
                    ),
                ),
            )

            val result = service.replacePhaseCheck(phaseId, request)

            assertEquals(1, result.questions.size)
            assertEquals("New?", result.questions.first().question)
            assertEquals(1, phase.checkQuestions.size)
        }

        @Test
        fun `throws 400 when a multiple choice question has fewer than two options`() {
            val phase = makePhaseWithCheck()
            every { onboardingPhaseRepository.findById(phaseId) } returns Optional.of(phase)

            val request = UpdatePhaseCheckRequest(
                questions = listOf(
                    UpdateCheckQuestionRequest(
                        position = 0,
                        type = CheckQuestionType.MULTIPLE_CHOICE,
                        question = "q",
                        options = listOf(UpdateCheckOptionRequest(0, "only one", true)),
                    ),
                ),
            )

            assertThrows<ResponseStatusException> {
                service.replacePhaseCheck(phaseId, request)
            }.also { assertEquals(400, it.statusCode.value()) }
        }

        @Test
        fun `throws 400 when a multiple choice question has no correct option`() {
            val phase = makePhaseWithCheck()
            every { onboardingPhaseRepository.findById(phaseId) } returns Optional.of(phase)

            val request = UpdatePhaseCheckRequest(
                questions = listOf(
                    UpdateCheckQuestionRequest(
                        position = 0,
                        type = CheckQuestionType.MULTIPLE_CHOICE,
                        question = "q",
                        options = listOf(
                            UpdateCheckOptionRequest(0, "a", false),
                            UpdateCheckOptionRequest(1, "b", false),
                        ),
                    ),
                ),
            )

            assertThrows<ResponseStatusException> {
                service.replacePhaseCheck(phaseId, request)
            }.also { assertEquals(400, it.statusCode.value()) }
        }

        @Test
        fun `throws 400 when a short text question has no correct answer`() {
            val phase = makePhaseWithCheck()
            every { onboardingPhaseRepository.findById(phaseId) } returns Optional.of(phase)

            val request = UpdatePhaseCheckRequest(
                questions = listOf(
                    UpdateCheckQuestionRequest(position = 0, type = CheckQuestionType.SHORT_TEXT, question = "q"),
                ),
            )

            assertThrows<ResponseStatusException> {
                service.replacePhaseCheck(phaseId, request)
            }.also { assertEquals(400, it.statusCode.value()) }
        }
    }

    @Nested
    inner class GetPhaseCheckAttemptsForUser {
        @Test
        fun `returns attempts for a user and phase`() {
            val phase = makePhaseWithCheck()
            val attempt = PhaseCheckAttempt(phase = phase, userId = userId, passed = false)
            every { userApi.exists(userId) } returns true
            every { onboardingPhaseRepository.findByIdAndPathUserId(phaseId, userId) } returns Optional.of(phase)
            every {
                phaseCheckAttemptRepository.findAllByPhaseIdAndUserIdOrderByCreatedAtDesc(phaseId, userId)
            } returns mutableListOf(attempt)

            val result = service.getPhaseCheckAttemptsForUser(userId, phaseId)

            assertEquals(1, result.attempts.size)
            assertEquals(2, result.attempts.first().questionCount)
        }

        @Test
        fun `throws 404 when user does not exist`() {
            every { userApi.exists(userId) } returns false

            assertThrows<ResponseStatusException> {
                service.getPhaseCheckAttemptsForUser(userId, phaseId)
            }.also { assertEquals(404, it.statusCode.value()) }
        }
    }

    @Nested
    inner class ReplacePhaseCheckDropsShortTextOptions {
        @Test
        fun `does not persist options or answer mismatch for short text`() {
            val phase = makePhaseWithCheck()
            every { onboardingPhaseRepository.findById(phaseId) } returns Optional.of(phase)
            every { onboardingPhaseRepository.save(any()) } answers { firstArg() }

            val request = UpdatePhaseCheckRequest(
                questions = listOf(
                    UpdateCheckQuestionRequest(
                        position = 0,
                        type = CheckQuestionType.SHORT_TEXT,
                        question = "cmd?",
                        correctAnswer = "run",
                    ),
                ),
            )

            service.replacePhaseCheck(phaseId, request)

            val stored = phase.checkQuestions.single()
            assertTrue(stored.options.isEmpty())
            assertEquals("run", stored.correctAnswer)
            assertNull(
                phase.checkQuestions
                    .first()
                    .options
                    .firstOrNull(),
            )
        }
    }
}
