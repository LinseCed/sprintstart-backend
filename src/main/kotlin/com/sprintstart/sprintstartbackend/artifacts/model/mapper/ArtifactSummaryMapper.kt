package com.sprintstart.sprintstartbackend.artifacts.model.mapper

import com.sprintstart.sprintstartbackend.artifacts.model.ai.AiArtifactSummaryResponse
import com.sprintstart.sprintstartbackend.artifacts.model.dto.response.ArtifactSummaryCitationResponse
import com.sprintstart.sprintstartbackend.artifacts.model.dto.response.ArtifactSummaryResponse
import com.sprintstart.sprintstartbackend.artifacts.model.entity.ArtifactSummary
import com.sprintstart.sprintstartbackend.artifacts.model.entity.ArtifactSummaryCitation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Maps between the AI service's summary contract, the persisted cache entity, and the API
 * response sent to the frontend.
 */
@Component
class ArtifactSummaryMapper {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Builds a new (unsaved) [ArtifactSummary] cache entity for [artifactId] from the AI's
     * response, generated against the artifact's current [sourceHash].
     *
     * A citation whose id isn't a valid UUID is dropped (logged, not fatal) rather than failing
     * the whole summary — the AI service's own citation ids are expected to be valid, but a
     * malformed one should degrade that one citation, not the summary itself.
     */
    fun toEntity(artifactId: UUID, sourceHash: String?, aiResponse: AiArtifactSummaryResponse): ArtifactSummary {
        val summary = ArtifactSummary(
            artifactId = artifactId,
            summary = aiResponse.summary,
            sourceHash = sourceHash,
        )

        summary.citations = aiResponse.citations
            .mapNotNull { citation ->
                val citedArtifactId = citation.artifactId.toUuidOrNull()
                if (citedArtifactId == null) {
                    logger.warn("Dropping citation with non-UUID artifact id {}", citation.artifactId)
                    return@mapNotNull null
                }
                ArtifactSummaryCitation(
                    artifactSummary = summary,
                    citedArtifactId = citedArtifactId,
                    filename = citation.filename,
                    sourceUrl = citation.sourceUrl,
                )
            }.toMutableList()

        return summary
    }

    fun toResponse(entity: ArtifactSummary): ArtifactSummaryResponse =
        ArtifactSummaryResponse(
            artifactId = entity.artifactId,
            summary = entity.summary,
            citations = entity.citations.map {
                ArtifactSummaryCitationResponse(
                    artifactId = it.citedArtifactId,
                    filename = it.filename,
                    sourceUrl = it.sourceUrl,
                )
            },
            generatedAt = entity.generatedAt,
        )

    private fun String.toUuidOrNull(): UUID? =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            null
        }
}
