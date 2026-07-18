package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request for the AI service's `/onboarding/verify` endpoint, `type = "knowledge"`.
 *
 * `exact`/`attest` are graded locally in Kotlin (see `VerificationService`), so [canonicalAnswer]
 * is never populated by this client.
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

/**
 * Repo/world-state evidence the backend has already, deterministically gathered for one PR --
 * mirrors `sprintstart-ai`'s `ArtifactEvidenceSchema` field-for-field. The AI service never
 * re-derives any of this, it only judges whether the content satisfies a rubric.
 */
@Serializable
data class ArtifactEvidenceDto(
    @SerialName("pr_title") val prTitle: String = "",
    @SerialName("pr_body") val prBody: String = "",
    @SerialName("pr_state") val prState: String = "",
    @SerialName("files_changed") val filesChanged: List<String> = emptyList(),
    @SerialName("checks_passed") val checksPassed: Boolean? = null,
    @SerialName("commit_messages") val commitMessages: List<String> = emptyList(),
)

/** Request for the AI service's `/onboarding/verify` endpoint, `type = "artifact"`. */
@Serializable
data class GradeArtifactRequest(
    val type: String = "artifact",
    val question: String,
    val rubric: String,
    @SerialName("artifact_evidence") val artifactEvidence: ArtifactEvidenceDto,
)

@Serializable
data class GradeResult(
    val passed: Boolean,
    val score: Double = 0.0,
    val feedback: String = "",
    val hint: String? = null,
)
