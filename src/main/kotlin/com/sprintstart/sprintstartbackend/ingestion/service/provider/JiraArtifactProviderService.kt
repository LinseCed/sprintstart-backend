package com.sprintstart.sprintstartbackend.ingestion.service.provider

import com.sprintstart.sprintstartbackend.ingestion.model.dto.command.JiraArtifactCommand
import com.sprintstart.sprintstartbackend.ingestion.model.entity.Artifact
import com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.ArtifactMetadataJsonMapper
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class JiraArtifactProviderService(
    private val ingestionRunRepository: IngestionRunRepository,
    private val artifactRepository: ArtifactRepository,
) {
    @Transactional
    fun persistArtifact(command: JiraArtifactCommand) {
        val runId = command.ingestionRunId

        val ingestionRun = ingestionRunRepository.findByIdForUpdate(runId).orElseThrow {
            IngestionRunNotFoundException(runId)
        }
        val artifact = Artifact(
            sourceSystem = command.sourceSystem,
            sourceId = command.sourceId,
            sourceUrl = command.sourceUrl,
            artifactType = command.artifactType,
            title = command.summary,
            content = command.description,
            mime = null,
            language = null,
            projectIdsInternal = mutableSetOf(),
            ingestionRun = ingestionRun,
            createdAtSource = command.createdAt,
            updatedAtSource = command.updatedAt,
            hash = null,
        )
        artifactRepository.save(artifact)
        ingestionRun.ingestedCount++
    }
}
