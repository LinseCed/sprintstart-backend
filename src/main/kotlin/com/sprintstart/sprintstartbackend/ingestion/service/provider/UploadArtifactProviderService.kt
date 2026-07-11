package com.sprintstart.sprintstartbackend.ingestion.service.provider

import com.sprintstart.sprintstartbackend.github.external.events.files.GithubFileDeletedEvent
import com.sprintstart.sprintstartbackend.ingestion.model.entity.Artifact
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import com.sprintstart.sprintstartbackend.ingestion.model.entity.FailedArtifact
import com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.SourceIdFactory
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import jakarta.transaction.Transactional
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * Owns writes to the ingestion artifact store and the mutable parts of `IngestionRun`.
 *
 * Connector listeners do not persist artifacts directly. They first map source-specific events to
 * ingestion commands, then delegate here so version-independent business rules stay in one place:
 * duplicate commits are ignored, files and issues update existing rows only when their effective
 * content changes, pull requests are treated as mutable records, and run counters are updated in
 * the same transaction as the underlying entity changes.
 */
@Service
class UploadArtifactProviderService(
    private val ingestionRunRepository: IngestionRunRepository,
    private val artifactRepository: ArtifactRepository,
) {
    /**
     * Persists or updates an ingestion artifact for the active ingestion run.
     *
     * Business rules:
     * - commits are idempotent by `sourceId`; an already-known commit is ignored
     * - files are updated only when the incoming content hash changes
     * - issues are updated only when the computed issue hash changes
     * - pull requests are always treated as mutable and overwrite title/body on re-fetch
     *
     * Counter side effects happen inside the same transaction:
     * - `ingestedCount` increments only when a new artifact row is created
     * - `updatedCount` increments only when an existing artifact is changed
     *
     * @param command [com.sprintstart.sprintstartbackend.ingestion.model.dto.command.GithubArtifactCommand] the command containing all data needed for ingestion.
     * @throws com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException when the referenced ingestion run does not exist.
     */
    @Transactional
    fun persistArtifact(command: UploadArtifactCommand) {
        val runId = command.ingestionRunId
        val projectId = command.projectId
        var artifact: Artifact?

        artifact = artifactRepository.findBySourceId(command.sourceId)
        if (artifact != null) {
            artifact.addProjectId(projectId)
            if (artifact.hash != command.hash) {
                artifact.content = command.content
                artifact.hash = command.hash
                ingestionRunRepository.incrementUpdatedCount(runId)
            }
            return
        }
        val ingestionRun = ingestionRunRepository.getReferenceById(command.ingestionRunId)
        artifact = Artifact(
            sourceSystem = command.sourceSystem,
            sourceId = command.sourceId,
            sourceUrl = null,
            artifactType = command.artifactType,
            title = command.title,
            content = command.content,
            mime = command.mime,
            language = command.language,
            projectIdsInternal = mutableSetOf(projectId),
            ingestionRun = ingestionRun,
            hash = command.hash,
            createdAtSource = null,
            updatedAtSource = null,
        )
        artifactRepository.save(artifact)
        ingestionRunRepository.incrementIngestedCount(runId)
    }


}


/**
 * Appends one failed source artifact to the current run and increments the aggregated failure
 * counter in the same transaction.
 *
 * The individual failed item is preserved for status/history views that need artifact-level
 * error details without scanning connector logs.
 *
 * @param command [com.sprintstart.sprintstartbackend.ingestion.model.dto.command.ArtifactFailedCommand] The command for a failed artifact containing all data needed.
 * @throws IngestionRunNotFoundException when the run id is unknown
 */
@Transactional
fun addFailedArtifact(command: ArtifactFailedCommand) {
    val run = ingestionRunRepository
        .findByIdOrNull(command.transactionId)
        ?: throw IngestionRunNotFoundException(command.transactionId)
    run.failedItems.add(
        FailedArtifact(
            sourceId = command.sourceId,
            reason = command.reason,
            artifactType = command.artifactType,
            sourceUrl = command.sourceUrl,
        ),
    )
    run.failedCount++
}

/**
 * Removes an ingestion file artifact when GitHub reports that the source file was deleted and
 * records its id for AI deindexing at the end of the run.
 *
 * This does not affect historic run counters. If no stored artifact exists for the deleted
 * source file, the method leaves the run unchanged.
 *
 * @param event [com.sprintstart.sprintstartbackend.github.external.events.files.GithubFileDeletedEvent] The event, emitted by the GitHub module, indicating a file deletion.
 * @throws IngestionRunNotFoundException when the run id is unknown.
 */
@Transactional
fun deleteFileArtifact(event: GithubFileDeletedEvent) {
    val run = ingestionRunRepository.findByIdOrNull(event.transactionId)
        ?: throw IngestionRunNotFoundException(event.transactionId)

    val sourceId = SourceIdFactory.buildSourceId(
        repositoryOwner = event.repositoryOwner,
        repositoryName = event.repositoryName,
        type = ArtifactType.FILE,
        unique = event.path,
    )
    val artifact = artifactRepository.findBySourceId(sourceId) ?: return

    artifactRepository.deleteById(artifact.id)

    run.artifactIdsToDeindex.add(artifact.id.toString())
}
}