package com.sprintstart.sprintstartbackend.ingestion.controller

import com.sprintstart.sprintstartbackend.canonical.model.dto.response.ArtifactPageResponse
import com.sprintstart.sprintstartbackend.ingestion.service.ArtifactQueryService
import com.sprintstart.sprintstartbackend.ingestion.service.ArtifactService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Read-only HTTP entry point for retrieving the stored content of one artifact.
 *
 * The endpoint is project-scoped so callers must supply both the project identifier used for
 * authorization and the artifact identifier used to locate the payload.
 */
@RestController
@Validated
@RequestMapping("/api/v1/")
@Tag(
    name = "Artifacts",
    description = "Read-only artifact content retrieval scoped to a project",
)
class ArtifactController(
    private val artifactService: ArtifactService,
    private val artifactQueryService: ArtifactQueryService,
) {

    @GetMapping("admin/artifacts")
    fun getAllArtifacts(
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam filter: String,
    ): ResponseEntity<ArtifactPageResponse> =
        ResponseEntity.ok(
            artifactQueryService.getAllArtifacts(page, size, filter),
        )

    /*@GetMapping("projects/{projectId}/artifacts")
    fun getProjectArtifacts(
        @RequestParam(defaultValue = "1") @Min(1) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @RequestParam filter: String,
        @PathVariable projectId: String
    ): ResponseEntity<ArtifactPageResponse> =
        ResponseEntity.ok(
            artifactQueryService.getProjectArtifacts(page, size, filter, projectId),
        )*/


    /**
     * Returns the raw stored payload of one artifact together with its effective mime type.
     *
     * The caller must have `USER` access to the given project, and the artifact must be linked to
     * that same project.
     *
     * @param projectId The SprintStart project that scopes access to the artifact.
     * @param artifactId The artifact whose stored content should be returned.
     * @param jwt The authenticated JWT used to resolve the caller subject.
     * @return The artifact payload with a response `Content-Type` derived from the stored mime type.
     */
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
