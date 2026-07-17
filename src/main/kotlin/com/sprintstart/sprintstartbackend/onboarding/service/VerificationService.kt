package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.SkipStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.model.entity.VerificationAttempt
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toSubmitResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.verification.SubmitVerificationAttemptRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.verification.UpsertVerificationRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.verification.SubmitVerificationAttemptResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.verification.VerificationResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingStepRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationAttemptRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Manages a step's "Verify" zone: config, grading orchestration, and the resulting unlock.
 *
 * [VerificationType.EXACT]/[VerificationType.ATTEST] are graded locally in Kotlin, mirroring the
 * AI service's own `grade_exact`/`grade_attest` logic exactly so a step behaves identically
 * regardless of which side happens to grade it. [VerificationType.KNOWLEDGE] is delegated to the
 * AI service (Seam 1) -- unlike [PhaseCheckService]'s fallback-to-exact-match on AI failure, a
 * failed AI call here surfaces a retryable `503` rather than a fabricated grade, since a rubric
 * has no safe local approximation.
 *
 * Passing writes [UserCompetencyState] with [CompetencySource.VERIFIED] and marks the step
 * [StepStatus.FINISHED] -- no separate "unlock" step is needed, since
 * [PathProjectionService][com.sprintstart.sprintstartbackend.onboarding.service.PathProjectionService]
 * already derives a competency's dependents' availability purely from the ledger. Submitting
 * works regardless of the step's current status (as long as it isn't already finished/skipped),
 * which is what makes "test-out" (passing without ever starting the lesson) just fall out of the
 * normal submission path rather than needing its own endpoint.
 */
@Suppress("TooManyFunctions")
@Service
class VerificationService(
    private val verificationRepository: VerificationRepository,
    private val verificationAttemptRepository: VerificationAttemptRepository,
    private val onboardingStepRepository: OnboardingStepRepository,
    private val competencyRepository: CompetencyRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val competencyGraphVersionService: CompetencyGraphVersionService,
    private val onboardingAiClient: OnboardingAiClient,
    private val userApi: UserApi,
    transactionManager: PlatformTransactionManager,
) {
    // The AI call is a long-running suspend operation, so it must not run inside a transaction --
    // same reasoning as CompetencyProposalService/BlueprintService.
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val logger = LoggerFactory.getLogger(javaClass)

//  ========================== Methods for users ==========================

    /**
     * Returns the verification config for a step in the authenticated user's path, without
     * revealing the rubric or canonical answer.
     *
     * @throws ResponseStatusException 404 if the user, step, or its verification doesn't exist.
     */
    @Transactional(readOnly = true)
    fun getVerificationForMe(authId: String, stepId: UUID): VerificationResponse {
        val userId = resolveUserId(authId)
        findStepForUser(stepId, userId)
        return findVerification(stepId).toResponse()
    }

    /**
     * Grades a submitted answer and, on pass, writes the ledger and completes the step.
     *
     * @throws ResponseStatusException 404 if the user, step, or its verification doesn't exist;
     * 400 if the step is already finished or skipped; 503 if [VerificationType.KNOWLEDGE] grading
     * needs the AI service and it's unavailable.
     */
    @Transactional
    fun submitAttemptForMe(
        authId: String,
        stepId: UUID,
        request: SubmitVerificationAttemptRequest,
    ): SubmitVerificationAttemptResponse {
        val userId = resolveUserId(authId)
        val step = findStepForUser(stepId, userId)
        val verification = findVerification(stepId)

        if (step.status == StepStatus.FINISHED || step.status == StepStatus.SKIPPED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A finished or skipped step can't be verified")
        }

        val attemptNo = verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) + 1
        val graded = grade(verification, step, request.answer, attemptNo)

        val attempt = verificationAttemptRepository.save(
            VerificationAttempt(
                verification = verification,
                userId = userId,
                answer = request.answer,
                passed = graded.passed,
                score = graded.score,
                feedback = graded.feedback,
                hint = graded.hint,
                attemptNo = attemptNo,
                graphVersion = competencyGraphVersionService.currentVersion(),
            ),
        )

        if (graded.passed) {
            writeVerifiedCompetencyState(userId, verification.competencyKey, verification.level)
            completeStep(step)
        }

        return attempt.toSubmitResponse(stepStatus = step.status)
    }

//  ========================== Methods for admins ==========================

    /**
     * Creates or replaces the verification config for a step.
     *
     * @throws ResponseStatusException 404 if the step or referenced competency doesn't exist; 400
     * if a type-required field ([Verification.rubric] for [VerificationType.KNOWLEDGE],
     * [Verification.canonicalAnswer] for [VerificationType.EXACT]) is missing.
     */
    @Transactional
    fun upsertVerification(stepId: UUID, request: UpsertVerificationRequest): VerificationResponse {
        val step = findStep(stepId)
        validateGradingConfig(request)
        if (!competencyRepository.existsByKey(request.competencyKey)) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No competency found with key: ${request.competencyKey}",
            )
        }

        val verification = verificationRepository.findByStepId(step.id)?.apply {
            type = request.type
            prompt = request.prompt
            rubric = request.rubric
            canonicalAnswer = request.canonicalAnswer
            competencyKey = request.competencyKey
            level = request.level
        } ?: Verification(
            stepId = step.id,
            type = request.type,
            prompt = request.prompt,
            rubric = request.rubric,
            canonicalAnswer = request.canonicalAnswer,
            competencyKey = request.competencyKey,
            level = request.level,
        )

        return verificationRepository.save(verification).toResponse()
    }

    /**
     * Triggers AI lesson synthesis for a step's verification and persists the grounded result.
     *
     * Runs outside any transaction for the AI call itself, matching
     * [CompetencyProposalService.generate]. A no-op on `"unchanged"`/`"skipped"` outcomes -- the
     * step's existing [OnboardingStep.content] is left untouched.
     *
     * @throws ResponseStatusException 404 if the step, its verification, or the referenced
     * competency doesn't exist.
     */
    suspend fun synthesizeContent(stepId: UUID) {
        val context = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadSynthesisContext(stepId) }!!
        }
        val outcome = onboardingAiClient.synthesizeLesson(
            competencyKey = context.competency.key,
            competencyLabel = context.competency.label,
            competencyDescription = context.competency.description ?: "",
            level = context.verification.level,
            lastFingerprint = context.step.lessonFingerprint,
        )
        val lesson = outcome.lesson
        if (outcome.status != "synthesized" || lesson == null) return

        withContext(Dispatchers.IO) {
            txTemplate.executeWithoutResult {
                val step = findStep(stepId)
                step.content = lesson.body
                step.lessonFingerprint = outcome.provenance?.corpusFingerprint
            }
        }
    }

//  ========================== Helper Methods ==========================

    private data class SynthesisContext(
        val step: OnboardingStep,
        val verification: Verification,
        val competency: Competency,
    )

    private fun loadSynthesisContext(stepId: UUID): SynthesisContext {
        val step = findStep(stepId)
        val verification = findVerification(stepId)
        val competency = competencyRepository.findByKey(verification.competencyKey)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No competency found with key: ${verification.competencyKey}",
            )
        return SynthesisContext(step, verification, competency)
    }

    private data class GradedResult(
        val passed: Boolean,
        val score: Double,
        val feedback: String,
        val hint: String? = null,
    )

    private fun grade(
        verification: Verification,
        step: OnboardingStep,
        answer: String,
        attemptNo: Int,
    ): GradedResult =
        when (verification.type) {
            VerificationType.EXACT -> gradeExact(verification.canonicalAnswer ?: "", answer)
            VerificationType.ATTEST -> gradeAttest(answer)
            VerificationType.KNOWLEDGE -> gradeKnowledge(verification, step, answer, attemptNo)
        }

    /** Normalized (case/whitespace-insensitive) exact match -- mirrors the AI service's `grade_exact`. */
    private fun gradeExact(canonicalAnswer: String, answer: String): GradedResult {
        val normalizedAnswer = answer.trim().lowercase().replace(WHITESPACE, " ")
        val normalizedCanonical = canonicalAnswer.trim().lowercase().replace(WHITESPACE, " ")
        return if (normalizedAnswer.isNotEmpty() && normalizedAnswer == normalizedCanonical) {
            GradedResult(passed = true, score = 1.0, feedback = "Matches exactly.")
        } else {
            GradedResult(
                passed = false,
                score = 0.0,
                feedback = "Does not match the expected answer.",
                hint = "Check the exact wording expected for this step.",
            )
        }
    }

    /** Self-confirmation: a non-blank answer is logged as passed, not judged -- mirrors `grade_attest`. */
    private fun gradeAttest(answer: String): GradedResult =
        if (answer.isNotBlank()) {
            GradedResult(passed = true, score = 1.0, feedback = "Self-attested.")
        } else {
            GradedResult(passed = false, score = 0.0, feedback = "No attestation submitted.")
        }

    /**
     * Delegates to the AI service's LLM judge. Unlike [PhaseCheckService]'s fallback on AI
     * failure, there is no safe local approximation for rubric-based judging, so an unavailable
     * AI service surfaces a retryable `503` instead of a fabricated grade.
     */
    private fun gradeKnowledge(
        verification: Verification,
        step: OnboardingStep,
        answer: String,
        attemptNo: Int,
    ): GradedResult {
        val rubric = verification.rubric
            ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Verification ${verification.id} is type KNOWLEDGE but has no rubric configured",
            )
        val result = try {
            runBlocking {
                onboardingAiClient.gradeKnowledge(
                    question = verification.prompt,
                    rubric = rubric,
                    evidence = step.content ?: "",
                    answer = answer,
                    attemptNo = attemptNo,
                )
            }
        } catch (@Suppress("SwallowedException") e: OnboardingAiException) {
            logger.warn("AI knowledge grading unavailable: {}", e.message)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Grading is temporarily unavailable, please try again",
            )
        }
        return GradedResult(
            passed = result.passed,
            score = result.score,
            feedback = result.feedback,
            hint = result.hint,
        )
    }

    /** Find-or-create write of the durable ledger, mirroring [AssessmentService]'s write pattern. */
    private fun writeVerifiedCompetencyState(userId: UUID, competencyKey: String, level: String) {
        val rank = LEVEL_RANKS[level.trim().lowercase()] ?: 0
        val existing = userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, competencyKey)
        if (existing != null) {
            existing.level = rank
            existing.source = CompetencySource.VERIFIED
            existing.updatedAt = Instant.now()
        } else {
            userCompetencyStateRepository.save(
                UserCompetencyState(
                    userId = userId,
                    competencyKey = competencyKey,
                    level = rank,
                    source = CompetencySource.VERIFIED,
                ),
            )
        }
    }

    /** Mirrors [OnboardingStepService.completeOnboardingStepForMe]'s completion logic. */
    private fun completeStep(step: OnboardingStep) {
        val completedAt = Instant.now()
        if (step.startedAt == null) {
            step.startedAt = completedAt
        }
        step.completedAt = completedAt
        if (step.skips.isNotEmpty() && step.skips.last().status == SkipStatus.PENDING) {
            step.skips.removeLast()
        }
        step.status = StepStatus.FINISHED
    }

    private fun validateGradingConfig(request: UpsertVerificationRequest) {
        if (request.type == VerificationType.KNOWLEDGE && request.rubric.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "KNOWLEDGE verifications need a rubric")
        }
        if (request.type == VerificationType.EXACT && request.canonicalAnswer.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "EXACT verifications need a canonicalAnswer")
        }
    }

    private fun resolveUserId(authId: String): UUID =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }

    private fun findStep(stepId: UUID): OnboardingStep =
        onboardingStepRepository
            .findById(stepId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }

    private fun findStepForUser(stepId: UUID, userId: UUID): OnboardingStep =
        onboardingStepRepository
            .findByIdAndPhasePathUserId(stepId, userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No step found with id: $stepId") }

    private fun findVerification(stepId: UUID): Verification =
        verificationRepository.findByStepId(stepId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No verification configured for step: $stepId")

    private companion object {
        val WHITESPACE = Regex("\\s+")

        // Aligned 1:1 with the AI SKILL_LEVELS (beginner..expert -> 1..4); unknown -> 0. Mirrors
        // AssessmentService.LEVEL_RANKS -- not shared, since both are 4 lines and belong to
        // different services' private grading concerns.
        val LEVEL_RANKS = mapOf(
            "beginner" to 1,
            "intermediate" to 2,
            "advanced" to 3,
            "expert" to 4,
        )
    }
}
