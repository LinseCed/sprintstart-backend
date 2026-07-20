package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.connectors.github.external.GithubRepositoryApi
import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.SkipStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.external.model.ArtifactEvidenceDto
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
 * regardless of which side happens to grade it. [VerificationType.KNOWLEDGE] and
 * [VerificationType.ARTIFACT] are delegated to the AI service (Seam 1) -- unlike
 * [PhaseCheckService]'s fallback-to-exact-match on AI failure, a failed AI call here surfaces a
 * retryable `503` rather than a fabricated grade, since a rubric has no safe local approximation.
 * [VerificationType.ARTIFACT] additionally gathers real GitHub state itself (via
 * [GithubRepositoryApi]) for the PR number a hire submits as their answer, before handing that
 * evidence to the AI's judge -- the highest-rigor rung of the ladder.
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
    private val githubRepositoryApi: GithubRepositoryApi,
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
     * Runs as two short transactions with the grading call in between, matching
     * [AssessmentService.answerAssessment]'s shape: [VerificationType.KNOWLEDGE]/
     * [VerificationType.ARTIFACT] grading is an AI (and, for artifacts, GitHub) round-trip, and
     * holding a DB connection open across it would pin one pool connection per in-flight
     * submission. The step's status is validated in both phases -- the write phase re-checks it
     * so a submission racing a concurrent finish/skip fails with the same 400 instead of
     * double-completing the step.
     *
     * @throws ResponseStatusException 404 if the user, step, or its verification doesn't exist;
     * 400 if the step is already finished or skipped; 503 if [VerificationType.KNOWLEDGE] or
     * [VerificationType.ARTIFACT] grading needs the AI service and it's unavailable.
     */
    suspend fun submitAttemptForMe(
        authId: String,
        stepId: UUID,
        request: SubmitVerificationAttemptRequest,
    ): SubmitVerificationAttemptResponse {
        val context = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadSubmissionContext(authId, stepId, request.answer) }!!
        }

        // An ARTIFACT answer is a PR number, and it is also the key the reuse check matches on, so
        // it is stored in the same normalized form it was looked up with.
        val answer = if (context.verification.type == VerificationType.ARTIFACT) {
            request.answer.trim()
        } else {
            request.answer
        }
        val graded = grade(context, answer)

        return withContext(Dispatchers.IO) {
            txTemplate.execute { persistGradedAttempt(context, answer, graded) }!!
        }
    }

    private data class SubmissionContext(
        val userId: UUID,
        val stepId: UUID,
        val verification: Verification,
        val lessonContent: String,
        val attemptNo: Int,
        /**
         * Another user already passed this verification with this exact answer.
         *
         * Only meaningful for `ARTIFACT`, where the answer is a pull request number. Resolved
         * here, inside the read transaction, so grading itself stays free of repository access.
         */
        val answerAlreadyClaimed: Boolean = false,
    )

    private fun loadSubmissionContext(authId: String, stepId: UUID, answer: String): SubmissionContext {
        val userId = resolveUserId(authId)
        val step = findStepForUser(stepId, userId)
        val verification = findVerification(stepId)
        requireStepNotConcluded(step)
        val attemptNo = verificationAttemptRepository.countByVerificationIdAndUserId(verification.id, userId) + 1
        return SubmissionContext(
            userId = userId,
            stepId = stepId,
            verification = verification,
            lessonContent = step.content ?: "",
            attemptNo = attemptNo,
            answerAlreadyClaimed = verification.type == VerificationType.ARTIFACT &&
                verificationAttemptRepository.existsByVerificationIdAndAnswerAndPassedIsTrueAndUserIdNot(
                    verification.id,
                    answer.trim(),
                    userId,
                ),
        )
    }

    private fun persistGradedAttempt(
        context: SubmissionContext,
        answer: String,
        graded: GradedResult,
    ): SubmitVerificationAttemptResponse {
        val step = findStepForUser(context.stepId, context.userId)
        val verification = findVerification(context.stepId)
        requireStepNotConcluded(step)

        val attempt = verificationAttemptRepository.save(
            VerificationAttempt(
                verification = verification,
                userId = context.userId,
                answer = answer,
                passed = graded.passed,
                score = graded.score,
                feedback = graded.feedback,
                hint = graded.hint,
                attemptNo = context.attemptNo,
                graphVersion = competencyGraphVersionService.currentVersion(),
            ),
        )

        if (graded.passed) {
            writeVerifiedCompetencyState(context.userId, verification.competencyKey, verification.level)
            completeStep(step)
        }

        return attempt.toSubmitResponse(stepStatus = step.status)
    }

    private fun requireStepNotConcluded(step: OnboardingStep) {
        if (step.status == StepStatus.FINISHED || step.status == StepStatus.SKIPPED) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A finished or skipped step can't be verified")
        }
    }

//  ========================== Methods for admins ==========================

    /**
     * Creates or replaces the verification config for a step.
     *
     * @throws ResponseStatusException 404 if the step or referenced competency doesn't exist; 400
     * if a type-required field ([Verification.rubric] for [VerificationType.KNOWLEDGE],
     * [Verification.canonicalAnswer] for [VerificationType.EXACT], or [Verification.rubric] +
     * [Verification.repositoryConnectionId] for [VerificationType.ARTIFACT]) is missing.
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
            repositoryConnectionId = request.repositoryConnectionId
            competencyKey = request.competencyKey
            level = request.level
        } ?: Verification(
            stepId = step.id,
            type = request.type,
            prompt = request.prompt,
            rubric = request.rubric,
            canonicalAnswer = request.canonicalAnswer,
            repositoryConnectionId = request.repositoryConnectionId,
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
     * @param forceRegenerate When true, omits the stored [OnboardingStep.lessonFingerprint] so the
     * AI service resynthesizes even if the corpus is unchanged -- used by
     * [ContentQualityService] to bypass idempotency once negative feedback crosses its threshold.
     * @throws ResponseStatusException 404 if the step, its verification, or the referenced
     * competency doesn't exist.
     */
    suspend fun synthesizeContent(stepId: UUID, forceRegenerate: Boolean = false) {
        val context = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadSynthesisContext(stepId) }!!
        }
        val outcome = onboardingAiClient.synthesizeLesson(
            competencyKey = context.competency.key,
            competencyLabel = context.competency.label,
            competencyDescription = context.competency.description ?: "",
            level = context.verification.level,
            lastFingerprint = if (forceRegenerate) null else context.step.lessonFingerprint,
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

    private suspend fun grade(
        context: SubmissionContext,
        answer: String,
    ): GradedResult {
        val verification = context.verification

        // Somebody else already passed this task with this pull request. One PR cannot be evidence
        // that two people did the work, and no rubric judgment can establish otherwise -- so this
        // is rejected here rather than sent to the judge, which would happily pass it again.
        if (context.answerAlreadyClaimed) {
            return GradedResult(
                passed = false,
                score = 0.0,
                feedback = "That pull request has already been submitted by someone else for this task.",
                hint = "Submit a pull request you opened yourself.",
            )
        }

        return when (verification.type) {
            VerificationType.EXACT -> gradeExact(verification.canonicalAnswer ?: "", answer)
            VerificationType.ATTEST -> gradeAttest(answer)
            VerificationType.KNOWLEDGE ->
                gradeKnowledge(verification, context.lessonContent, answer, context.attemptNo)

            VerificationType.ARTIFACT -> gradeArtifact(verification, answer)
        }
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
    private suspend fun gradeKnowledge(
        verification: Verification,
        lessonContent: String,
        answer: String,
        attemptNo: Int,
    ): GradedResult {
        val rubric = verification.rubric
            ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Verification ${verification.id} is type KNOWLEDGE but has no rubric configured",
            )
        val result = try {
            onboardingAiClient.gradeKnowledge(
                question = verification.prompt,
                rubric = rubric,
                evidence = lessonContent,
                answer = answer,
                attemptNo = attemptNo,
            )
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

    /**
     * Gathers real GitHub state for a hire-submitted PR number, then delegates rubric judgment to
     * the AI service's `type: "artifact"` judge -- the highest-rigor rung of the ladder. Unlike
     * [gradeKnowledge], the AI service never sees GitHub itself, so an unparseable answer or a PR
     * that doesn't exist in the linked repository is graded locally as a fail without an AI call --
     * those aren't judgment calls, they're facts only Kotlin can observe.
     */
    @Suppress("ThrowsCount")
    private suspend fun gradeArtifact(verification: Verification, answer: String): GradedResult {
        val prNumber = answer.trim().toIntOrNull()
            ?: return GradedResult(
                passed = false,
                score = 0.0,
                feedback = "Submit a PR number to verify this task.",
                hint = "Open a PR that addresses the task, then submit its number.",
            )
        val repositoryConnectionId = verification.repositoryConnectionId
            ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Verification ${verification.id} is type ARTIFACT but has no repositoryConnectionId configured",
            )
        val rubric = verification.rubric
            ?: throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Verification ${verification.id} is type ARTIFACT but has no rubric configured",
            )

        val evidence = githubRepositoryApi.getPullRequestEvidence(repositoryConnectionId, prNumber)
            ?: return GradedResult(
                passed = false,
                score = 0.0,
                feedback = "No PR #$prNumber found in the linked repository.",
                hint = "Double-check the PR number and that it's on the linked repository.",
            )

        val result = try {
            onboardingAiClient.gradeArtifact(
                taskDescription = verification.prompt,
                rubric = rubric,
                evidence = ArtifactEvidenceDto(
                    prTitle = evidence.title,
                    prBody = evidence.body,
                    prState = evidence.state,
                    filesChanged = evidence.filesChanged,
                    checksPassed = evidence.checksPassed,
                    commitMessages = evidence.commitMessages,
                ),
            )
        } catch (@Suppress("SwallowedException") e: OnboardingAiException) {
            logger.warn("AI artifact grading unavailable: {}", e.message)
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

    /**
     * Find-or-create write of the durable ledger, mirroring [AssessmentService]'s write pattern.
     *
     * Monotonic: passing a verification whose target level is below what the ledger already
     * records keeps the higher level ("never un-earns progress" applies to the ledger too) --
     * only the source still upgrades to [CompetencySource.VERIFIED], since the pass is proof at
     * at least that level.
     */
    private fun writeVerifiedCompetencyState(userId: UUID, competencyKey: String, level: String) {
        val rank = LEVEL_RANKS[level.trim().lowercase()] ?: 0
        val existing = userCompetencyStateRepository.findByUserIdAndCompetencyKey(userId, competencyKey)
        if (existing != null) {
            existing.level = maxOf(existing.level, rank)
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

    @Suppress("ThrowsCount")
    private fun validateGradingConfig(request: UpsertVerificationRequest) {
        // An unrecognized level would rank to 0 on pass, which the projection never counts as
        // mastered -- the hire would pass the check without ever unlocking dependents. Reject it
        // here, at config time, instead of failing silently at grading time.
        if (LEVEL_RANKS[request.level.trim().lowercase()] == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "level must be one of: ${LEVEL_RANKS.keys.joinToString(", ")}",
            )
        }
        if (request.type == VerificationType.KNOWLEDGE && request.rubric.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "KNOWLEDGE verifications need a rubric")
        }
        if (request.type == VerificationType.EXACT && request.canonicalAnswer.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "EXACT verifications need a canonicalAnswer")
        }
        if (request.type == VerificationType.ARTIFACT) {
            if (request.rubric.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ARTIFACT verifications need a rubric")
            }
            if (request.repositoryConnectionId == null) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "ARTIFACT verifications need a repositoryConnectionId",
                )
            }
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
