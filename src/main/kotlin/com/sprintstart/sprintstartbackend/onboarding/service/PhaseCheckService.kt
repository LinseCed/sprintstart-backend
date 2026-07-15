package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.PhaseCheckAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CheckQuestionType
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeAnswerItem
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeAnswerResult
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPhase
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckAnswer
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckAttempt
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckOption
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckQuestion
import com.sprintstart.sprintstartbackend.onboarding.model.entity.PhaseCheckReviewItem
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.stepsCompleted
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCheckForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCheckResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCheckSummaryResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toForUserResponse
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
import com.sprintstart.sprintstartbackend.onboarding.repository.PhaseCheckQuestionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.PhaseCheckReviewItemRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
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
    private val phaseCheckQuestionRepository: PhaseCheckQuestionRepository,
    private val phaseCheckReviewItemRepository: PhaseCheckReviewItemRepository,
    private val userApi: UserApi,
    private val phaseCheckAiClient: PhaseCheckAiClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        /** Minimum percentage of correct questions required to pass a phase check. */
        const val PASS_PERCENT = 80
        const val PERCENT = 100
    }

    /** Grading outcome of one question: whether it was correct plus optional AI feedback. */
    private data class Graded(
        val correct: Boolean,
        val feedback: String? = null,
    )

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
        val phase = findPhaseForUser(phaseId, userId)

        val base = phase.toCheckForUserResponse()
        val reviewQuestions = loadOpenReviewQuestions(userId, phaseId).map { (_, question) ->
            question.toForUserResponse().copy(review = true, reviewSourcePhaseTitle = question.phase.title)
        }

        return base.copy(questions = base.questions + reviewQuestions)
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
        val ownQuestions = phase.checkQuestions.sortedBy { it.position }
        // Carried-over repeat questions from earlier phases the user must also answer here.
        val reviewPairs = loadOpenReviewQuestions(userId, phaseId)
        val graded = gradeQuestions(ownQuestions + reviewPairs.map { it.second }, answersByQuestionId)

        val ownResults = ownQuestions.map { question ->
            val outcome = graded.getValue(question.id)
            question.toResultResponse(correct = outcome.correct, feedback = outcome.feedback)
        }
        val reviewResults = reviewPairs.map { (_, question) ->
            val outcome = graded.getValue(question.id)
            question
                .toResultResponse(correct = outcome.correct, feedback = outcome.feedback)
                .copy(review = true, reviewSourcePhaseTitle = question.phase.title)
        }

        // Only the phase's own questions count toward the pass threshold; repeats are
        // an extra verification and never block passing (integer math avoids float surprises).
        val correctCount = ownResults.count { it.correct }
        val passed = correctCount * PERCENT >= ownQuestions.size * PASS_PERCENT

        val attempt = PhaseCheckAttempt(phase = phase, userId = userId, passed = passed)
        (ownQuestions + reviewPairs.map { it.second }).forEach { question ->
            val submitted = answersByQuestionId[question.id]
            attempt.answers += PhaseCheckAnswer(
                attempt = attempt,
                questionId = question.id,
                selectedOptionIds = submitted?.selectedOptionIds?.toMutableList() ?: mutableListOf(),
                textAnswer = submitted?.textAnswer,
                correct = graded.getValue(question.id).correct,
            )
        }
        // save() merges (the id is client-assigned), so use the returned managed instance.
        // The attempt is NOT added to phase.checkAttempts by hand: that would put a detached
        // copy into a cascade collection and fail the flush with a NonUniqueObjectException.
        // toCheckSummaryResponse() below lazily reloads the collection, which includes this attempt.
        val savedAttempt = phaseCheckAttemptRepository.save(attempt)

        if (passed) {
            applyCarryOver(phase, userId, ownQuestions, reviewPairs, graded)
        }

        return SubmitPhaseCheckAttemptResponse(
            attemptId = savedAttempt.id,
            phaseId = phase.id,
            passed = passed,
            createdAt = savedAttempt.createdAt,
            correctCount = correctCount,
            questionCount = ownQuestions.size,
            requiredPercent = PASS_PERCENT,
            phaseCheckSummary = phase.toCheckSummaryResponse(),
            nextPhaseUnlocked = passed && phase.stepsCompleted() && phase.hasNextPhase(),
            results = ownResults + reviewResults,
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
     * Grades every question of an attempt, keyed by question id.
     *
     * Multiple choice is graded deterministically in process (exact set of correct
     * options). Short text is delegated to the AI service for semantic grading in a
     * single batch call, because users rarely type the reference answer verbatim.
     */
    private fun gradeQuestions(
        questions: List<PhaseCheckQuestion>,
        answersByQuestionId: Map<UUID, SubmitCheckAnswerRequest>,
    ): Map<UUID, Graded> {
        val multipleChoice = questions
            .filter { it.type == CheckQuestionType.MULTIPLE_CHOICE }
            .associate { it.id to Graded(gradeMultipleChoice(it, answersByQuestionId[it.id])) }

        val shortText = gradeShortText(
            questions.filter { it.type == CheckQuestionType.SHORT_TEXT },
            answersByQuestionId,
        )

        return multipleChoice + shortText
    }

    /** A multiple choice answer is correct when it selects exactly the correct options. */
    private fun gradeMultipleChoice(question: PhaseCheckQuestion, answer: SubmitCheckAnswerRequest?): Boolean {
        if (answer == null) return false
        val correctOptionIds = question.options
            .filter { it.correct }
            .map { it.id }
            .toSet()
        return answer.selectedOptionIds.toSet() == correctOptionIds
    }

    /**
     * Grades short-text answers semantically via the AI service in one batch.
     *
     * Blank answers (or questions without a reference answer) are marked incorrect
     * without calling the AI. If the AI service is unavailable, grading falls back to
     * a trimmed, case-insensitive comparison so that submitting an attempt never fails
     * on grading alone.
     */
    private fun gradeShortText(
        questions: List<PhaseCheckQuestion>,
        answersByQuestionId: Map<UUID, SubmitCheckAnswerRequest>,
    ): Map<UUID, Graded> {
        if (questions.isEmpty()) return emptyMap()

        val graded = mutableMapOf<UUID, Graded>()
        val toGrade = mutableListOf<GradeAnswerItem>()
        questions.forEach { question ->
            val answer = answersByQuestionId[question.id]?.textAnswer?.trim()
            val reference = question.correctAnswer?.trim()
            if (answer.isNullOrBlank() || reference.isNullOrBlank()) {
                graded[question.id] = Graded(correct = false)
            } else {
                toGrade += GradeAnswerItem(
                    id = question.id.toString(),
                    question = question.question,
                    referenceAnswer = reference,
                    userAnswer = answer,
                )
            }
        }
        if (toGrade.isEmpty()) return graded

        val aiResults = gradeWithAi(toGrade)
        toGrade.forEach { item ->
            val questionId = UUID.fromString(item.id)
            val ai = aiResults?.get(item.id)
            graded[questionId] = if (ai != null) {
                Graded(correct = ai.correct, feedback = ai.feedback.ifBlank { null })
            } else {
                // AI unavailable: fall back to a deterministic comparison.
                Graded(correct = item.userAnswer.equals(item.referenceAnswer, ignoreCase = true))
            }
        }
        return graded
    }

    /**
     * Calls the AI grading service, returning results keyed by correlation id, or
     * `null` when the service is unavailable so the caller can fall back.
     */
    private fun gradeWithAi(items: List<GradeAnswerItem>): Map<String, GradeAnswerResult>? =
        try {
            runBlocking { phaseCheckAiClient.gradeAnswers(items) }.associateBy { it.id }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn("AI short-text grading unavailable, falling back to exact match: {}", e.message)
            null
        }

    private fun PhaseCheckQuestion.toResultResponse(
        correct: Boolean,
        feedback: String? = null,
    ): CheckAnswerResultResponse {
        return CheckAnswerResultResponse(
            questionId = this.id,
            correct = correct,
            correctOptionIds = options.filter { it.correct }.map { it.id },
            correctAnswer = this.correctAnswer,
            explanation = this.explanation,
            feedback = feedback,
        )
    }

    private fun OnboardingPhase.hasNextPhase(): Boolean {
        return path.phases.any { it.position > this.position }
    }

    /** The phase directly after this one by position, or null when this is the last phase. */
    private fun OnboardingPhase.nextPhase(): OnboardingPhase? =
        path.phases.filter { it.position > this.position }.minByOrNull { it.position }

    /**
     * Loads the open (unresolved) carried-over questions the user must re-answer in the
     * given phase, paired with their original [PhaseCheckQuestion]. Items whose question
     * no longer exists are skipped.
     */
    private fun loadOpenReviewQuestions(
        userId: UUID,
        phaseId: UUID,
    ): List<Pair<PhaseCheckReviewItem, PhaseCheckQuestion>> {
        val items = phaseCheckReviewItemRepository
            .findAllByUserIdAndTargetPhaseIdAndResolvedFalseOrderByCreatedAtAsc(userId, phaseId)
        if (items.isEmpty()) return emptyList()

        val questionsById = phaseCheckQuestionRepository
            .findAllById(items.map { it.questionId })
            .associateBy { it.id }

        return items.mapNotNull { item -> questionsById[item.questionId]?.let { item to it } }
    }

    /**
     * Updates carry-over state after a phase was passed.
     *
     * Repeat questions shown in this attempt are resolved when answered correctly and
     * advanced to the next phase when answered incorrectly. The phase's own questions
     * answered incorrectly in any attempt (so understanding is verified even after a
     * lucky retry) are carried over to the next phase. When there is no next phase,
     * nothing new is carried and any remaining repeats are dropped.
     */
    private fun applyCarryOver(
        phase: OnboardingPhase,
        userId: UUID,
        ownQuestions: List<PhaseCheckQuestion>,
        reviewPairs: List<Pair<PhaseCheckReviewItem, PhaseCheckQuestion>>,
        graded: Map<UUID, Graded>,
    ) {
        val nextPhase = phase.nextPhase()

        reviewPairs.forEach { (item, question) ->
            when {
                graded[question.id]?.correct == true -> item.resolved = true
                nextPhase != null -> item.targetPhaseId = nextPhase.id
                else -> item.resolved = true
            }
        }
        phaseCheckReviewItemRepository.saveAll(reviewPairs.map { it.first })

        if (nextPhase == null) return
        everWrongOwnQuestionIds(phase.id, userId, ownQuestions).forEach { questionId ->
            val alreadyOpen = phaseCheckReviewItemRepository
                .findAllByUserIdAndQuestionIdAndResolvedFalse(userId, questionId)
                .isNotEmpty()
            if (!alreadyOpen) {
                phaseCheckReviewItemRepository.save(
                    PhaseCheckReviewItem(
                        userId = userId,
                        questionId = questionId,
                        sourcePhaseId = phase.id,
                        targetPhaseId = nextPhase.id,
                    ),
                )
            }
        }
    }

    /** Ids of the phase's own questions answered incorrectly in at least one of the user's attempts. */
    private fun everWrongOwnQuestionIds(
        phaseId: UUID,
        userId: UUID,
        ownQuestions: List<PhaseCheckQuestion>,
    ): Set<UUID> {
        val ownIds = ownQuestions.map { it.id }.toSet()
        return phaseCheckAttemptRepository
            .findAllByPhaseIdAndUserIdOrderByCreatedAtDesc(phaseId, userId)
            .flatMap { it.answers }
            .filter { !it.correct && it.questionId in ownIds }
            .map { it.questionId }
            .toSet()
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
