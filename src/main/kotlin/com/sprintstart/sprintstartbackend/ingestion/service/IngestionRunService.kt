package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.model.dto.response.IngestionRunResponse
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRun
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Reads recent ingestion runs for API consumers.
 *
 * This service keeps pagination and response mapping out of the controller so the API surface can
 * stay stable even if the persistence model grows additional run metadata later.
 */
@Service
class IngestionRunService(
    private val ingestionRunRepository: IngestionRunRepository,
) {
    /**
     * Returns the newest ingestion runs first.
     *
     * @param limit maximum number of runs returned from the first page of run history
     * @return API-ready run summaries including counters and failed items
     * @throws IllegalArgumentException If Spring Data rejects the requested page size.
     */
    @Transactional(readOnly = true)
    @Tracked("Retrieving recent ingestion runs")
    fun getRecentRuns(
        limit: Int = 10,
    ): List<IngestionRunResponse> =
        ingestionRunRepository
            .findByOrderByStartedAtDesc(
                PageRequest.of(0, limit),
            ).map { it.toResponse() }
}

/**
 * Maps an ingestion run entity to its API representation, deriving the stable `sourceId`
 * ("owner/name") from the persisted source-instance metadata when both parts are present.
 */
internal fun IngestionRun.toResponse(): IngestionRunResponse =
    IngestionRunResponse(
        runId = id,
        sourceSystem = sourceSystem,
        sourceId = if (owner != null && name != null) "$owner/$name" else null,
        owner = owner,
        name = name,
        repositoryId = repositoryId,
        startedAt = startedAt,
        finishedAt = finishedAt,
        ingestedCount = ingestedCount,
        updatedCount = updatedCount,
        deletedCount = deletedCount,
        failedCount = failedCount,
        failedItems = failedItems,
        status = status,
        failureReason = failureReason,
        aiSyncStatus = aiSyncStatus,
        aiSyncFailureReason = aiSyncFailureReason,
    )
