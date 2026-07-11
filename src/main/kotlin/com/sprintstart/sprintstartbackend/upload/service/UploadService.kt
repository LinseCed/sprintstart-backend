package com.sprintstart.sprintstartbackend.upload.service

import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.ArtifactUploadedEvent
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadArtifactOperationOutcome
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadArtifactStatus
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadBatchDeletionFinishedEvent
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadBatchFinishedEvent
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadFileDeletedEvent
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadStartedEvent
import com.sprintstart.sprintstartbackend.upload.model.dto.response.UploadArtifactResponse
import com.sprintstart.sprintstartbackend.upload.model.dto.response.UploadListItemResponse
import com.sprintstart.sprintstartbackend.upload.model.entity.UploadedArtifact
import com.sprintstart.sprintstartbackend.upload.repository.LinkedImageRepository
import com.sprintstart.sprintstartbackend.upload.repository.UploadedArtifactRepository
import com.sprintstart.sprintstartbackend.upload.service.storage.ArtifactStorageService
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.UUID

@Service
class UploadService(
    private val uploadedArtifactRepository: UploadedArtifactRepository,
    private val linkedImageRepository: LinkedImageRepository,
    private val userApi: UserApi,
    private val validationService: UploadValidationService,
    private val storageService: ArtifactStorageService,
    private val artifactLinkingService: ArtifactLinkingService,
    private val publisher: ApplicationEventPublisher,
) {
    @Transactional
    fun upload(
        authId: String,
        files: List<MultipartFile>,
        projectId: UUID,
        uploaderId: UUID,
    ): List<UploadArtifactResponse> {
        val transactionId = UUID.randomUUID()
        publisher.publishEvent(UploadStartedEvent(transactionId = transactionId))
        val userInRepo = userApi.getUserByAuthId(authId)
        if (projectId !in userInRepo.projects.map { it.projectId }) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No access to project")
        }
        if (!userApi.exists(uploaderId)) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Uploader with id $uploaderId does not exist",
            )
        }
        val uploadedArtifacts = mutableSetOf<UploadedArtifact>()

        val responses = mutableListOf<UploadArtifactResponse>()
        val uploadArtifactOperationOutcomes = mutableSetOf<UploadArtifactOperationOutcome>()

        val uploadedArtifactsByFilename =
            mutableMapOf<String, UploadedArtifact>()

        val markdownArtifacts =
            mutableListOf<Pair<UploadedArtifact, String>>()

        files.forEach { file ->

            try {
                val uploadResult = uploadSingle(
                    file = file,
                    uploaderId = uploaderId,
                    transactionId = transactionId,
                    projectId = projectId,
                    outcomes = uploadArtifactOperationOutcomes,
                )

                responses.add(uploadResult.response)

                uploadResult.artifact?.let { artifact ->
                    uploadedArtifacts.add(artifact)

                    uploadedArtifactsByFilename[
                        artifact.filename,
                    ] = artifact

                    if (
                        artifact.mime.contains("markdown")
                    ) {
                        markdownArtifacts.add(
                            artifact to String(file.bytes),
                        )
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught")
                ex: Exception,
            ) {
                uploadArtifactOperationOutcomes.add(
                    UploadArtifactOperationOutcome(
                        id = null,
                        filename = file.originalFilename
                            ?: "unknown",
                        status = UploadArtifactStatus.FAILED,
                        error = ex.message,
                    ),
                )
                responses.add(
                    UploadArtifactResponse(
                        id = null,
                        filename = file.originalFilename
                            ?: "unknown",
                        status = "failed",
                        error = ex.message,
                    ),
                )
            }
        }

        val linkedImages = artifactLinkingService.linkMarkdownImages(
            markdownArtifacts = markdownArtifacts,
            uploadedArtifactsByFilename =
            uploadedArtifactsByFilename,
        )

        publisher.publishEvent(
            UploadBatchFinishedEvent(
                transactionId = transactionId,
                uploaderId = uploaderId,
                artifactsId = uploadedArtifacts.map { it.id }.toSet(),
                linkedImages = linkedImages.map { it.id }.toSet(),
                uploadArtifactOperationOutcomes = uploadArtifactOperationOutcomes,
            ),
        )

        return responses
    }

    private fun uploadSingle(
        file: MultipartFile,
        uploaderId: UUID,
        projectId: UUID,
        transactionId: UUID,
        outcomes: MutableSet<UploadArtifactOperationOutcome>,
    ): UploadResult {
        validationService.validate(file)

        val bytes = file.bytes

        val hash = sha256(bytes)

        val existingArtifact =
            uploadedArtifactRepository.findByHash(hash)

        if (existingArtifact != null) {
            outcomes.add(
                UploadArtifactOperationOutcome(
                    id = existingArtifact.id,
                    filename = existingArtifact.filename,
                    status = UploadArtifactStatus.ALREADY_UPLOADED,
                ),
            )
            return UploadResult(
                response = UploadArtifactResponse(
                    id = existingArtifact.id,
                    filename = existingArtifact.filename,
                    status = "ok",
                ),
                artifact = existingArtifact,
            )
        }

        val artifact = UploadedArtifact(
            filename = file.originalFilename!!,
            hash = hash,
            mime = file.contentType
                ?: "application/octet-stream",
            storagePath = "",
            uploaderId = uploaderId,
        )

        val storagePath = storageService.store(
            file = file,
            artifactId = artifact.id,
        )

        artifact.storagePath = storagePath

        uploadedArtifactRepository.save(artifact)

        publisher.publishEvent(
            ArtifactUploadedEvent(
                transactionId = transactionId,
                projectId = projectId,
                artifactId = artifact.id,
                filename = artifact.filename,
                storagePath = artifact.storagePath,
                mime = artifact.mime,
                uploaderId = artifact.uploaderId,
                uploadedAt = artifact.uploadedAt,
                hash = artifact.hash,
            ),
        )
        outcomes.add(
            UploadArtifactOperationOutcome(
                id = artifact.id,
                filename = artifact.filename,
                status = UploadArtifactStatus.STORED,
            ),
        )
        return UploadResult(
            response = UploadArtifactResponse(
                id = artifact.id,
                filename = artifact.filename,
                status = "ok",
            ),
            artifact = artifact,
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val digest =
            MessageDigest.getInstance("SHA-256")

        return digest
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it)
            }
    }

    data class UploadResult(
        val response: UploadArtifactResponse,
        val artifact: UploadedArtifact?,
    )

    fun listUploads(
        uploaderId: UUID,
    ): List<UploadListItemResponse> =
        uploadedArtifactRepository
            .findAllByUploaderId(uploaderId)
            .map {
                UploadListItemResponse(
                    id = it.id,
                    filename = it.filename,
                    mime = it.mime,
                    uploadedAt = it.uploadedAt,
                )
            }

    @Transactional
    fun deleteUpload(
        authId: String,
        artifactIds: Set<UUID>,
        removerId: UUID,
        projectId: UUID,
    ) {
        val userInRepo = userApi.getUserByAuthId(authId)
        if (projectId !in userInRepo.projects.map { it.projectId }) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "No access to project")
        }
        if (!userApi.exists(removerId)) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Remover with id $removerId does not exist",
            )
        }
        val deleteArtifactOutcomes = mutableSetOf<UploadArtifactOperationOutcome>()
        val transactionId = UUID.randomUUID()
        publisher.publishEvent(
            UploadStartedEvent(
                transactionId = transactionId,
            ),
        )
        artifactIds.forEach { artifactId ->
            val artifact =
                uploadedArtifactRepository
                    .findById(artifactId)
                    .orElse(null)
            if (artifact == null) {
                deleteArtifactOutcomes.add(
                    UploadArtifactOperationOutcome(
                        id = artifactId,
                        filename = "unknown",
                        status = UploadArtifactStatus.FAILED,
                        error = "Artifact with id $artifactId not found.",
                    ),
                )
                return@forEach
            }

            linkedImageRepository
                .deleteAllByArtifactId(
                    artifactId,
                )

            linkedImageRepository
                .deleteAllByImageArtifactId(
                    artifactId,
                )

            try {
                storageService.delete(
                    artifact.storagePath,
                )
            } catch (e: Exception) {
                deleteArtifactOutcomes.add(
                    UploadArtifactOperationOutcome(
                        id = artifactId,
                        filename = "unknown",
                        status = UploadArtifactStatus.FAILED,
                        error = e.message,
                    ),
                )
            }
            publisher.publishEvent(
                UploadFileDeletedEvent(
                    transactionId = transactionId,
                    uploadArtifactId = artifact.id,
                ),
            )
            uploadedArtifactRepository.delete(
                artifact,
            )
        }
        publisher.publishEvent(
            UploadBatchDeletionFinishedEvent(
                transactionId = transactionId,
                removerId = removerId,
                deleteArtifactOutcomes = deleteArtifactOutcomes,
            ),
        )
    }
}
