package com.sprintstart.sprintstartbackend.artifacts.controller

import com.sprintstart.sprintstartbackend.artifacts.model.dto.response.ArtifactSummaryResponse
import com.sprintstart.sprintstartbackend.artifacts.service.ArtifactSummaryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Endpoints exposing AI-generated artifact summaries.
 */
@RestController
@RequestMapping("/api/v1/artifacts")
@Tag(name = "Artifacts", description = "Artifact-related endpoints")
class ArtifactSummaryController(
    private val artifactSummaryService: ArtifactSummaryService,
) {
    /**
     * Returns a summary of the given artifact, generating and caching one if none is cached yet
     * or the artifact's content has since changed.
     */
    @Operation(
        summary = "Get an artifact summary",
        description = "Returns an AI-generated summary of the artifact, cached until its content changes.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Summary returned successfully"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "404", description = "No artifact found for the given id"),
            ApiResponse(responseCode = "500", description = "The AI service failed to generate a summary"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/{artifactId}/summary")
    @PreAuthorize("hasRole('USER')")
    suspend fun getSummary(
        @PathVariable artifactId: UUID,
    ): ArtifactSummaryResponse {
        return artifactSummaryService.getSummary(artifactId)
    }
}
