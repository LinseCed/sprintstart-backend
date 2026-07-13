package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CheckQuestionType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPhase
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckAnswer
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckAttempt
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckOption
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckQuestion
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.stepsCompleted
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCheckForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCheckResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCheckSummaryResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.check.SubmitCheckAnswerRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.check.SubmitPhaseCheckAttemptRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.check.UpdatePhaseCheckRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.check.CheckAnswerResultResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.check.GetPhaseCheckAttemptsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.check.GetPhaseCheckForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.check.GetPhaseCheckResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.check.SubmitPhaseCheckAttemptResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPhaseRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.PhaseCheckAttemptRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Manages phase-level knowledge checks.
 *
 * A knowledge check belongs to an onboarding phase (not to individual steps) and
 * consists of the phase's check questions. A phase counts as "checked" once any
 * submitted attempt passed. Correct answers are only revealed in submit results,
 * never when loading the check.
 */
@Suppress("TooManyFunctions")
@Service
class PhaseCheckService(
    private val onboardingPhaseRepository: OnboardingPhaseRepository,
    private val phaseCheckAttemptRepository: PhaseCheckAttemptRepository,
    private val userApi: UserApi,
) {
//  ========================== Methods for users ==========================

    /**
     * Returns the knowledge check of a phase in the authenticated user's path,
     * without exposing correct answers.
     *
     * @param authId External authentication identifier.
     * @param phaseId Identifier of the phase whose check should be loaded.
     * @return The check questions to render, without correct answers.
     * @throws ResponseStatusException When the user or phase does not exist.
     */
    @Transactional(readOnly = true)
    fun getPhaseCheckForMe(authId: String, phaseId: UUID): GetPhaseCheckForUserResponse {
        val userId = resolveUserId(authId)

        return findPhaseForUser(phaseId, userId).toCheckForUserResponse()
    }

    /**
     * Grades and stores a knowledge check attempt for a phase in the authenticated
     * user's path.
     *
     * The attempt passes when every question is answered correctly. The response
     * reveals the correct answers per question so the frontend can show the result.
     *
     * @param authId External authentication identifier.
     * @param phaseId Identifier of the phase whose check is being taken.
     * @param request The user's answers.
     * @return The graded attempt including per-question results.
     * @throws ResponseStatusException When the user or phase does not exist or the
     * phase has no knowledge check.
     */
    @Transactional
    fun submitPhaseCheckAttemptForMe(
        authId: String,
        phaseId: UUID,
        request: SubmitPhaseCheckAttemptRequest,
    ): SubmitPhaseCheckAttemptResponse {
        val userId = resolveUserId(authId)
        val phase = findPhaseForUser(phaseId, userId)

        if (phase.checkQuestions.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No knowledge check configured for phase: $phaseId")
        }

        val answersByQuestionId = request.answers.associateBy { it.questionId }
        val questions = phase.checkQuestions.sortedBy { it.position }
        val results = questions.map { question ->
            question.toResultResponse(correct = gradeAnswer(question, answersByQuestionId[question.id]))
        }
        val passed = results.all { it.correct }

        val attempt = PhaseCheckAttempt(phase = phase, userId = userId, passed = passed)
        questions.zip(results).forEach { (question, result) ->
            val submitted = answersByQuestionId[question.id]
            attempt.answers += PhaseCheckAnswer(
                attempt = attempt,
                questionId = question.id,
                selectedOptionIds = submitted?.selectedOptionIds?.toMutableList() ?: mutableListOf(),
                textAnswer = submitted?.textAnswer,
                correct = result.correct,
            )
        }
        phaseCheckAttemptRepository.save(attempt)
        // Keep the entity graph in sync so the summary below sees the new attempt.
        phase.checkAttempts += attempt

        return SubmitPhaseCheckAttemptResponse(
            attemptId = attempt.id,
            phaseId = phase.id,
            passed = passed,
            createdAt = attempt.createdAt,
            phaseCheckSummary = phase.toCheckSummaryResponse(),
            nextPhaseUnlocked = passed && phase.stepsCompleted() && phase.hasNextPhase(),
            results = results,
        )
    }

//  ========================== Methods for admins ==========================

    /**
     * Returns the knowledge check of a phase including correct answers, for
     * admin-facing editing screens.
     *
     * @param phaseId Identifier of the phase whose check should be loaded.
     * @return The check questions including correct answers.
     * @throws ResponseStatusException When the phase does not exist.
     */
    @Transactional(readOnly = true)
    fun getPhaseCheck(phaseId: UUID): GetPhaseCheckResponse {
        return findPhase(phaseId).toCheckResponse()
    }

    /**
     * Replaces all knowledge check questions of a phase.
     *
     * Existing questions are removed and the submitted questions become the new
     * check. Submitted attempts are kept as history; their answers reference the
     * old question IDs.
     *
     * @param phaseId Identifier of the phase whose check should be replaced.
     * @param request The new check questions.
     * @return The stored check questions including correct answers.
     * @throws ResponseStatusException When the phase does not exist or a question
     * is invalid for its type.
     */
    @Transactional
    fun replacePhaseCheck(phaseId: UUID, request: UpdatePhaseCheckRequest): GetPhaseCheckResponse {
        val phase = findPhase(phaseId)

        validateQuestions(request)

        phase.checkQuestions.clear()
        request.questions.sortedBy { it.position }.forEach { questionRequest ->
            val question = PhaseCheckQuestion(
                phase = phase,
                position = questionRequest.position,
                type = questionRequest.type,
                question = questionRequest.question,
                explanation = questionRequest.explanation,
                correctAnswer = questionRequest.correctAnswer
                    .takeIf { questionRequest.type == CheckQuestionType.SHORT_TEXT },
            )
            if (questionRequest.type == CheckQuestionType.MULTIPLE_CHOICE) {
                questionRequest.options.sortedBy { it.position }.forEach { optionRequest ->
                    question.options += PhaseCheckOption(
                        question = question,
                        position = optionRequest.position,
                        label = optionRequest.label,
                        correct = optionRequest.correct,
                    )
                }
            }
            phase.checkQuestions += question
        }

        return onboardingPhaseRepository.save(phase).toCheckResponse()
    }

    /**
     * Returns all submitted knowledge check attempts of a user for one phase so
     * admins, PMs, or HR can review the results.
     *
     * @param userId Identifier of the user whose attempts should be loaded.
     * @param phaseId Identifier of the phase whose attempts should be loaded.
     * @return The user's attempts, newest first.
     * @throws ResponseStatusException When the user or phase does not exist.
     */
    @Transactional(readOnly = true)
    fun getPhaseCheckAttemptsForUser(userId: UUID, phaseId: UUID): GetPhaseCheckAttemptsResponse {
        if (!userApi.exists(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with id: $userId")
        }
        val phase = findPhaseForUser(phaseId, userId)

        val attempts = phaseCheckAttemptRepository.findAllByPhaseIdAndUserIdOrderByCreatedAtDesc(phaseId, userId)

        return GetPhaseCheckAttemptsResponse(
            userId = userId,
            phaseId = phaseId,
            attempts = attempts.map { it.toGetResponse(questionCount = phase.checkQuestions.size) },
        )
    }

//  ========================== Helper Methods ==========================

    private fun resolveUserId(authId: String): UUID {
        return userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }
    }

    private fun findPhase(phaseId: UUID): OnboardingPhase {
        return onboardingPhaseRepository
            .findById(phaseId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No phase found with id: $phaseId") }
    }

    private fun findPhaseForUser(phaseId: UUID, userId: UUID): OnboardingPhase {
        return onboardingPhaseRepository
            .findByIdAndPathUserId(phaseId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No phase found with id: $phaseId") }
    }

    /**
     * Grades one submitted answer against its question. A missing answer counts as
     * incorrect. Multiple choice requires the exact set of correct options; short
     * text is compared trimmed and case-insensitive.
     */
    private fun gradeAnswer(question: PhaseCheckQuestion, answer: SubmitCheckAnswerRequest?): Boolean {
        if (answer == null) return false

        return when (question.type) {
            CheckQuestionType.MULTIPLE_CHOICE -> {
                val correctOptionIds = question.options
                    .filter { it.correct }
                    .map { it.id }
                    .toSet()
                answer.selectedOptionIds.toSet() == correctOptionIds
            }

            CheckQuestionType.SHORT_TEXT -> {
                val expected = question.correctAnswer?.trim() ?: return false
                answer.textAnswer?.trim()?.equals(expected, ignoreCase = true) == true
            }
        }
    }

    private fun PhaseCheckQuestion.toResultResponse(correct: Boolean): CheckAnswerResultResponse {
        return CheckAnswerResultResponse(
            questionId = this.id,
            correct = correct,
            correctOptionIds = options.filter { it.correct }.map { it.id },
            correctAnswer = this.correctAnswer,
            explanation = this.explanation,
        )
    }

    private fun OnboardingPhase.hasNextPhase(): Boolean {
        return path.phases.any { it.position > this.position }
    }

    private fun badRequest(message: String): Nothing =
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)

    private fun validateQuestions(request: UpdatePhaseCheckRequest) {
        request.questions.forEach { question ->
            when (question.type) {
                CheckQuestionType.MULTIPLE_CHOICE -> {
                    if (question.options.size < 2) {
                        badRequest("Multiple choice questions need at least 2 options")
                    }
                    if (question.options.none { it.correct }) {
                        badRequest("Multiple choice questions need at least 1 correct option")
                    }
                }

                CheckQuestionType.SHORT_TEXT -> {
                    if (question.correctAnswer.isNullOrBlank()) {
                        badRequest("Short text questions need a correctAnswer")
                    }
                }
            }
        }
    }
}
