package com.sprintstart.sprintstartbackend.artifacts

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.artifacts.model.ai.AiArtifactSummaryRequest
import com.sprintstart.sprintstartbackend.artifacts.model.ai.AiArtifactSummaryResponse
import com.sprintstart.sprintstartbackend.artifacts.model.exceptions.ArtifactSummaryAiException
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import com.sprintstart.sprintstartbackend.shared.web.WebClientException
import org.springframework.stereotype.Component
import java.net.URI
import java.util.UUID

/**
 * Artifacts module HTTP wrapper for the AI artifact-summary service.
 *
 * This is the only artifact-summary class that knows about HTTP or URIs. It builds URIs from the
 * configured AI base URL, maps domain types onto [WebClient] calls, and translates transport
 * failures ([WebClientException]) into a module-local domain exception
 * ([ArtifactSummaryAiException]). It holds no business logic (in particular, no caching); that
 * belongs to the service layer above.
 */
@Component
class ArtifactSummaryAiClient(
    private val webClient: WebClient,
    private val applicationConfig: ApplicationConfig,
) {
    /**
     * Requests a fresh summary of [artifactId] from the AI service.
     *
     * @throws ArtifactSummaryAiException if the AI service returns a non-2xx status.
     */
    suspend fun summarize(artifactId: UUID, request: AiArtifactSummaryRequest): AiArtifactSummaryResponse =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/artifacts/$artifactId/summary"))
                .body(request)
                .sync()
                .perform<AiArtifactSummaryResponse>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            throw ArtifactSummaryAiException(
                "Failed to summarize artifact $artifactId (HTTP ${e.statusCode}): ${e.body}",
            )
        }

    private fun uri(path: String): URI = URI.create("${applicationConfig.ai.baseUrl}$path")
}
