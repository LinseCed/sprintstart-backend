package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.model.entity.FinishedTypes
import com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Builds the compact "latest status per source" view used by operational UIs.
 *
 * Unlike run history, this service collapses the persistence model down to the latest known run
 * for each exposed source system and also defines the empty-state behavior when a source has never
 * run.
 */
@Service
class GithubIngestionRunService(
    private val ingestionRunRepository: IngestionRunRepository,
    private val ingestionRunLifeCycleService: IngestionRunLifeCycleService,
) {
    @Transactional
    fun markFetchPhaseFinished(runId: UUID, finishedType: FinishedTypes) {
        val run = ingestionRunRepository
            .findByIdForUpdate(runId)
            .orElseThrow { IngestionRunNotFoundException(runId) }
        run.finishedTypes.add(finishedType)
        if (run.finishedTypes.containsAll(FinishedTypes.entries)) {
            ingestionRunLifeCycleService.finishRun(run)
        }
    }
}
