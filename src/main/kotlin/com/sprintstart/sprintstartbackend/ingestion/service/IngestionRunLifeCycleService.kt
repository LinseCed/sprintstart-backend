package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.model.dto.command.ArtifactFailedCommand
import com.sprintstart.sprintstartbackend.ingestion.model.entity.FailedArtifact
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRun
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import jakarta.transaction.Transactional
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class ArtifactIngestionService (private val ingestionRunRepository: IngestionRunRepository){
    /**
     * Creates an ingestion run before connector work begins.
     *
     * The initial status is controlled by the caller so listeners can distinguish between
     * connection setup, active fetching, and immediate startup failures. If the run already
     * exists, the mutable lifecycle fields are updated instead of creating a duplicate row.
     *
     * @param transactionId The id of the transaction to start.
     * @param sourceSystem [com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem] The source of the new run.
     * @param status [com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus] The status of the ingestion run.
     * @param failureReason Optional run-level failure reason for lifecycle failures.
     */
    @Transactional
    fun startRun(
        transactionId: UUID,
        sourceSystem: SourceSystem,
        status: IngestionRunStatus,
        failureReason: String? = null,
    ) {
        val ingestionRun = ingestionRunRepository.findByIdOrNull(transactionId)
        if (ingestionRun == null) {
            val ingestionRun = IngestionRun(
                id = transactionId,
                sourceSystem = sourceSystem,
                status = status,
                failureReason = failureReason,
                finishedAt = if (status == IngestionRunStatus.FAILED) Instant.now() else null,
            )
            ingestionRunRepository.save(ingestionRun)
        } else {
            ingestionRun.status = status
            ingestionRun.finishedAt = if (status == IngestionRunStatus.FAILED) Instant.now() else null
            ingestionRun.failureReason = failureReason
        }
    }

    /**
     * Updates the run status inside a transaction so listener-triggered state transitions are
     * persisted even when they only mutate the managed entity.
     *
     * @param transactionId The id of the transaction to update the status of.
     * @param status [IngestionRunStatus] The new status.
     * @throws com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException when the run id is unknown
     */
    @Transactional
    fun updateRunStatus(transactionId: UUID, status: IngestionRunStatus) {
        val run = ingestionRunRepository
            .findByIdOrNull(transactionId)
            ?: throw IngestionRunNotFoundException(transactionId)
        run.status = status
    }

    fun finishRun(

    )

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
}