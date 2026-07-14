package com.sprintstart.sprintstartbackend.artifacts.model.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Artifact summary returned by the AI service.
 */
@Serializable
data class AiArtifactSummaryResponse(
    @SerialName("artifact_id")
    val artifactId: String,
    val summary: String,
    val citations: List<AiArtifactSummaryCitation>,
)

/**
 * A source artifact the AI cited while generating the summary.
 *
 * The AI service only sends the citation's own [artifactId]/[filename]/[sourceUrl]; it does not
 * know about this backend's separate ingested-vs-uploaded artifact split.
 */
@Serializable
data class AiArtifactSummaryCitation(
    @SerialName("artifact_id")
    val artifactId: String,
    val filename: String,
    @SerialName("source_url")
    val sourceUrl: String? = null,
)
