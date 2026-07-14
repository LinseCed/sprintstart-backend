package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.model.dto.response.ArtifactContentRedirectResponse
import com.sprintstart.sprintstartbackend.ingestion.model.dto.response.ArtifactContentResponse
import com.sprintstart.sprintstartbackend.ingestion.model.dto.response.ArtifactContentResult
import com.sprintstart.sprintstartbackend.ingestion.model.entity.Artifact
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import com.sprintstart.sprintstartbackend.upload.external.UploadedArtifactReader
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Resolves project-scoped artifact open targets for authenticated callers.
 *
 * This service centralizes the authorization and ownership checks required before raw artifact
 * payloads or remote source URLs can be returned to the API layer.
 */
@Service
class ArtifactService(
    private val userApi: UserApi,
    private val artifactRepository: ArtifactRepository,
    private val uploadedArtifactReader: UploadedArtifactReader,
) {
    /**
     * Loads one artifact open target when the authenticated user has access to the requested project.
     *
     * The method first verifies project access through the user module, then ensures the artifact
     * exists and is linked to the same project. Stored text content is returned directly for any
     * source, upload artifacts can return original stored bytes, and remote artifacts without local
     * bytes redirect to their source URL when available.
     *
     * @param projectId The project that scopes access to the artifact.
     * @param artifactId The identifier of the artifact to retrieve.
     * @param authId The authenticated caller subject from the JWT.
     * @return Stored artifact bytes or a source URL redirect target.
     * @throws ResponseStatusException `403` when the caller has no access to the project.
     * @throws ResponseStatusException `404` when the artifact is missing, not linked to the
     * requested project, or has no stored content.
     */
    @Transactional(readOnly = true)
    fun getArtifactContent(projectId: UUID, artifactId: UUID, authId: String): ArtifactContentResult {
        ensureAccessToProject(authId, projectId)
        val artifact = requireArtifact(artifactId)

        if (projectId !in artifact.projectIds) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "This artifact does not belong to project with id $projectId.",
            )
        }
        artifact.content?.let {
            return ArtifactContentResponse(
                content = it.toByteArray(Charsets.UTF_8),
                mime = artifact.mime ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
            )
        }

        when (artifact.sourceSystem) {
            SourceSystem.UPLOAD -> {
                readUploadedBytes(artifact)?.let {
                    return ArtifactContentResponse(
                        content = it,
                        mime = artifact.mime ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    )
                }
            }

            SourceSystem.GITHUB,
            SourceSystem.JIRA,
            -> {
                artifact.sourceUrl?.let {
                    return ArtifactContentRedirectResponse(it)
                }
            }
        }

        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact content not found")
    }

    private fun readUploadedBytes(artifact: Artifact): ByteArray? {
        val uploadArtifactId = runCatching {
            UUID.fromString(artifact.sourceId)
        }.getOrElse {
            return null
        }

        return runCatching {
            uploadedArtifactReader.readBytes(uploadArtifactId)
        }.getOrElse {
            return null
        }
    }

    /**
     * Verifies project access through the user module before artifact content is exposed.
     *
     * @param authId The authenticated caller subject from the JWT.
     * @param projectId The project whose artifacts the caller wants to read.
     * @throws ResponseStatusException `403` when the caller has no access to the project.
     */
    fun ensureAccessToProject(authId: String, projectId: UUID) {
        val userHasAccessToProject = userApi.userHasAccessToProject(authId, projectId)
        if (!userHasAccessToProject) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No access to project with id $projectId")
        }
    }

    /**
     * Loads an artifact or converts the missing-row case into the API-level not-found exception.
     *
     * @param artifactId The artifact id to resolve.
     * @return The persisted artifact entity.
     * @throws ResponseStatusException `404` when no artifact exists for the id.
     */
    fun requireArtifact(artifactId: UUID): Artifact {
        return artifactRepository
            .findById(artifactId)
            .orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found")
            }
    }
}
