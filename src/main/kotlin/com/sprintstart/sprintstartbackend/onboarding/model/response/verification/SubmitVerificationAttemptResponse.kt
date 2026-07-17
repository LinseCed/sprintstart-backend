package com.sprintstart.sprintstartbackend.onboarding.model.response.verification

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import java.util.UUID

/** Grading result of a submitted verification attempt. */
data class SubmitVerificationAttemptResponse(
    val attemptId: UUID,
    val stepId: UUID,
    val passed: Boolean,
    val score: Double,
    val feedback: String,
    // Escalating hint for the next attempt; null when passed.
    val hint: String? = null,
    val attemptNo: Int,
    val graphVersion: Int,
    val stepStatus: StepStatus,
)
