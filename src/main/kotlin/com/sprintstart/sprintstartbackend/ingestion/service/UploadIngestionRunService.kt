package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.UploadArtifactFailedMapper
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadArtifactStatus
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadBatchDeletionFinishedEvent
import com.sprintstart.sprintstartbackend.upload.external.events.ingestion.UploadBatchFinishedEvent
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

/**
 * Builds the compact "latest status per source" view used by operational UIs.
 *
 * Unlike run history, this service collapses the persistence model down to the latest known run
 * for each exposed source system and also defines the empty-state behavior when a source has never
 * run.
 */
@Service
class UploadIngestionRunService(
    private val ingestionRunRepository: IngestionRunRepository,
    private val failedArtifactService: FailedArtifactService,
    private val uploadArtifactFailedMapper: UploadArtifactFailedMapper,
    private val ingestionRunLifeCycleService: IngestionRunLifeCycleService,
) {
    @Transactional
    fun finishUploadIngestionRun(event: UploadBatchFinishedEvent) {
        val run = ingestionRunRepository
            .findByIdForUpdate(event.transactionId)
            .orElseThrow { IngestionRunNotFoundException(event.transactionId) }
        val outcomes = event.uploadArtifactOperationOutcomes
        outcomes.forEach {
            when (it.status) {
                UploadArtifactStatus.FAILED,
                -> {
                    failedArtifactService.addFailedArtifact(
                        uploadArtifactFailedMapper.toCommand(
                            event = event,
                            outcome = it,
                            operation = "upload",
                        ),
                    )
                }

                else -> {}
            }
        }
        ingestionRunLifeCycleService.finishRun(run)
    }

    @Transactional
    fun finishUploadDeletionIngestionRun(event: UploadBatchDeletionFinishedEvent) {
        val run = ingestionRunRepository
            .findByIdForUpdate(event.transactionId)
            .orElseThrow { IngestionRunNotFoundException(event.transactionId) }
        event.deleteArtifactOutcomes.forEach { outcome ->
            when (outcome.status) {
                UploadArtifactStatus.FAILED -> {
                    failedArtifactService.addFailedArtifact(
                        uploadArtifactFailedMapper.toCommand(
                            event = event,
                            outcome = outcome,
                            operation = "deletion",
                        ),
                    )
                }

                else -> {}
            }
        }
        ingestionRunLifeCycleService.finishRun(run)
    }
}
