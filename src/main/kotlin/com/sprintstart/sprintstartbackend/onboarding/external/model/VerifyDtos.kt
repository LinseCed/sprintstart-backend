package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request for the AI service's `/onboarding/verify` endpoint.
 *
 * The backend only ever sends `type = "knowledge"` here -- `exact`/`attest` are graded locally in
 * Kotlin (see `VerificationService`), so [canonicalAnswer] is never populated by this client.
 */
@Serializable
data class GradeKnowledgeRequest(
    val type: String = "knowledge",
    val question: String,
    val answer: String,
    @SerialName("attempt_no") val attemptNo: Int,
    val rubric: String,
    val evidence: String = "",
    @SerialName("canonical_answer") val canonicalAnswer: String? = null,
)

@Serializable
data class GradeResult(
    val passed: Boolean,
    val score: Double = 0.0,
    val feedback: String = "",
    val hint: String? = null,
)
