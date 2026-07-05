package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.model.dto.response.ArtifactContentResponse
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ArtifactService(
    private val userApi: UserApi,
    private val artifactRepository: ArtifactRepository,
) {
    @Transactional(readOnly = true)
    fun getArtifactContent(projectId: UUID, artifactId: UUID, authId: String): ArtifactContentResponse {
        val userHasAccessToProject = userApi.userHasAccessToProject(authId, projectId)
        if (!userHasAccessToProject){
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No access to project with id $projectId")
        }
        val artifact = artifactRepository.findById(artifactId)
            .orElseThrow{
                ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found")
            }
        if(projectId !in artifact.projectIds){
            throw ResponseStatusException(HttpStatus.NOT_FOUND,
                "This artifact does not belong to project with id $projectId.")
        }
        val bodyText = artifact.bodyText?.toByteArray(Charsets.UTF_8)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact content not found")

        return ArtifactContentResponse(
            content = bodyText,
            mime = artifact.mime?:MediaType.APPLICATION_OCTET_STREAM_VALUE
        )
    }
}