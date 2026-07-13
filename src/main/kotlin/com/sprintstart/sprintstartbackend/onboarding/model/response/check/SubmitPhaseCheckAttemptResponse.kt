package com.sprintstart.sprintstartbackend.onboarding.model.response.check

import java.time.Instant
import java.util.UUID

/**
 * Grading result of a submitted knowledge check attempt. This is the only user-facing
 * place where correct answers are revealed.
 */
data class SubmitPhaseCheckAttemptResponse(
    val attemptId: UUID,
    val phaseId: UUID,
    val passed: Boolean,
    val createdAt: Instant,
    val phaseCheckSummary: PhaseCheckSummaryResponse,
    val nextPhaseUnlocked: Boolean,
    val results: List<CheckAnswerResultResponse>,
)

data class CheckAnswerResultResponse(
    val questionId: UUID,
    val correct: Boolean,
    val correctOptionIds: List<UUID> = emptyList(),
    val correctAnswer: String? = null,
    val explanation: String? = null,
)
