package com.sprintstart.sprintstartbackend.ingestion.controller

import com.sprintstart.sprintstartbackend.ingestion.service.ArtifactService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Read-only HTTP entry point for browsing recent ingestion executions.
 *
 * This endpoint is intended for operational views that need a compact run history rather than
 * artifact-level details.
 */
@RestController
@Validated
@RequestMapping("/api/v1")
@Tag(
    name = "Ingestion Runs",
    description = "Read-only history of ingestion runs with per-run counters and timing",
)
class ArtifactController(
    private val artifactService: ArtifactService,
) {
    @GetMapping("/projects/{projectId}/artifacts/{artifactId}/content")
    @PreAuthorize("hasRole('USER')")
    fun getArtifactContent(
        @PathVariable projectId : UUID,
        @PathVariable artifactId : UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ByteArray> {
        val response = artifactService.getArtifactContent(projectId = projectId, artifactId = artifactId, authId = jwt.subject)
        val mediaType = runCatching { MediaType.parseMediaType(response.mime) }.getOrDefault(MediaType.APPLICATION_OCTET_STREAM)
        return ResponseEntity.ok().contentType(mediaType).body(response.content)

    }

}
