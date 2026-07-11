package com.sprintstart.sprintstartbackend.ingestion.model.mapper

import com.sprintstart.sprintstartbackend.ingestion.model.FileMetaDataResolver
import com.sprintstart.sprintstartbackend.ingestion.model.dto.UploadArtifactMetadata
import com.sprintstart.sprintstartbackend.ingestion.model.dto.command.UploadArtifactCommand
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import com.sprintstart.sprintstartbackend.upload.external.UploadedArtifactReader
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.ArtifactUploadedEvent
import org.springframework.stereotype.Component

/**
 * Translates GitHub domain events into canonical artifact commands.
 *
 * The mapper normalizes source-specific identifiers, derives stable hashes where the ingestion
 * service uses change detection, and enriches file artifacts with lightweight metadata such as
 * mime type and language.
 */
@Component
class UploadArtifactMapper(
    private val uploadedArtifactReader: UploadedArtifactReader,
) {
    /**
     * Maps a fetched commit into the canonical command shape.
     *
     * The commit title is intentionally shortened to keep list views compact while preserving the
     * full message in `bodyText`.
     */
    fun toCommand(event: ArtifactUploadedEvent): UploadArtifactCommand {
        val extension = when (event.filename.lowercase()) {
            "dockerfile" -> "dockerfile"
            else -> event.filename.substringAfterLast(".", "").lowercase()
        }
        val bodyText = uploadedArtifactReader.readText(event.artifactId)
        val language = FileMetaDataResolver.languageFor(extension)
        return UploadArtifactCommand(
            ingestionRunId = event.transactionId,
            projectId = event.projectId,
            sourceSystem = SourceSystem.UPLOAD,
            sourceId = event.artifactId.toString(),
            artifactType = ArtifactType.FILE,
            title = event.filename,
            content = bodyText,
            mime = event.mime,
            language = language,
            createdAtSource = event.uploadedAt,
            updatedAtSource = null,
            hash = event.hash,
            metadata = UploadArtifactMetadata(
                storagePath = event.storagePath,
                actorId = event.uploaderId,
            ),
        )
    }
}
