package com.sprintstart.sprintstartbackend.onboarding.model.response.verification

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import java.util.UUID

/**
 * Grading result of a submitted verification attempt.
 *
 * [stepId] and [stepStatus] are null for a module-owned check: a shared module has no per-user row
 * to report the status of, which is exactly what makes it shared. Passing writes the ledger, and
 * the node's state is derived from there.
 */
data class SubmitVerificationAttemptResponse(
    val attemptId: UUID,
    val stepId: UUID? = null,
    val moduleId: UUID? = null,
    val passed: Boolean,
    val score: Double,
    val feedback: String,
    // Escalating hint for the next attempt; null when passed.
    val hint: String? = null,
    val attemptNo: Int,
    val graphVersion: Int,
    val stepStatus: StepStatus? = null,
)
