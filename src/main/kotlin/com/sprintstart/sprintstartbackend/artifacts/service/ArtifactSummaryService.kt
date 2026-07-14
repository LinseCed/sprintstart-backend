package com.sprintstart.sprintstartbackend.artifacts.service

import com.sprintstart.sprintstartbackend.artifacts.ArtifactSummaryAiClient
import com.sprintstart.sprintstartbackend.artifacts.model.ai.AiArtifactSummaryRequest
import com.sprintstart.sprintstartbackend.artifacts.model.dto.response.ArtifactSummaryResponse
import com.sprintstart.sprintstartbackend.artifacts.model.mapper.ArtifactSummaryMapper
import com.sprintstart.sprintstartbackend.artifacts.repository.ArtifactSummaryRepository
import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.upload.external.UploadApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Serves artifact summaries, generating and caching them via the AI service.
 *
 * Generating a summary is a real LLM call, so a fresh one is only requested when there is no
 * cached summary for the artifact, or the cached one was generated from different content (its
 * stored hash no longer matches the artifact's current hash). An artifact with no content hash
 * (a legacy/edge-case ingested artifact) cannot be cached and is summarized fresh on every call.
 *
 * An artifact can be either ingested (via a connector) or directly uploaded; both are checked
 * through their modules' exported read-only APIs rather than reaching into their repositories.
 */
@Service
class ArtifactSummaryService(
    private val artifactSummaryRepository: ArtifactSummaryRepository,
    private val artifactSummaryAiClient: ArtifactSummaryAiClient,
    private val artifactSummaryMapper: ArtifactSummaryMapper,
    private val artifactIngestionApi: ArtifactIngestionApi,
    private val uploadApi: UploadApi,
) {
    /**
     * Returns the summary of [artifactId], serving it from the cache when still valid.
     *
     * @throws ResponseStatusException 404 if no artifact with [artifactId] exists (ingested or
     *   uploaded).
     * @throws com.sprintstart.sprintstartbackend.artifacts.model.exceptions.ArtifactSummaryAiException
     *   if the AI service fails to generate a summary.
     */
    @Transactional
    suspend fun getSummary(artifactId: UUID): ArtifactSummaryResponse {
        val currentHash = resolveHash(artifactId)

        val cached = currentHash?.let { hash ->
            artifactSummaryRepository.findById(artifactId).orElse(null)?.takeIf { it.sourceHash == hash }
        }
        if (cached != null) {
            return artifactSummaryMapper.toResponse(cached)
        }

        val aiResponse = artifactSummaryAiClient.summarize(artifactId, AiArtifactSummaryRequest())
        val entity = artifactSummaryMapper.toEntity(artifactId, currentHash, aiResponse)

        if (currentHash != null) {
            artifactSummaryRepository.save(entity)
        }

        return artifactSummaryMapper.toResponse(entity)
    }

    /**
     * Resolves the current content hash of [artifactId] (null means "exists, but no hash on
     * record", which disables caching for it — see class docs).
     *
     * @throws ResponseStatusException 404 if no artifact with [artifactId] exists at all (ingested
     *   or uploaded).
     */
    private fun resolveHash(artifactId: UUID): String? {
        val uploadedHash = uploadApi.getHash(artifactId)
        if (uploadedHash != null) {
            return uploadedHash
        }

        if (artifactIngestionApi.exists(artifactId)) {
            return artifactIngestionApi.getHash(artifactId)
        }

        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact $artifactId not found")
    }
}
