package com.sprintstart.sprintstartbackend.artifacts.model.dto.response

import java.time.Instant
import java.util.UUID

/**
 * The response sent to the frontend for a summarized artifact.
 */
data class ArtifactSummaryResponse(
    val artifactId: UUID,
    val summary: String,
    val citations: List<ArtifactSummaryCitationResponse>,
    val generatedAt: Instant,
)

data class ArtifactSummaryCitationResponse(
    val artifactId: UUID,
    val filename: String,
    val sourceUrl: String?,
)
