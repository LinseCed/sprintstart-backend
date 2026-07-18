package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.ArtifactIngestionClient
import com.sprintstart.sprintstartbackend.ingestion.model.dto.request.RunArtifactsAiSyncRequest
import com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.ingestion.ArtifactAiMapper
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.jvm.optionals.getOrElse

/**
 * Sends the final AI indexing payload for a completed ingestion run.
 *
 * The service builds one batch containing newly stored run artifacts and artifact ids that were
 * deleted during the same run and must be removed from the AI index. Database reads stay on
 * [Dispatchers.IO]; the HTTP call happens after the request is built.
 */
@Service
class RunArtifactsIngestionService(
    private val ingestionRunRepository: IngestionRunRepository,
    private val artifactRepository: ArtifactRepository,
    private val artifactAiMapper: ArtifactAiMapper,
    private val artifactIngestionClient: ArtifactIngestionClient,
    transactionManager: PlatformTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Reads plus the artifactAiMapper.toIngestRequest mapping must share one session: Artifact.labels
    // is lazy, so mapping it after the session closes throws LazyInitializationException.
    private val readTxTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    /**
     * Loads the run output, skips empty runs, and dispatches the batched ingest/deindex request.
     *
     * Empty runs are intentionally ignored because there is nothing for the AI layer to index or
     * remove. Repository reads (and the lazy-collection mapping they drive) are executed on
     * [Dispatchers.IO] inside a read-only transaction before the outbound HTTP call.
     *
     * @param runId The completed ingestion run whose artifacts should be synced to AI.
     * @throws IngestionRunNotFoundException when the run id does not exist.
     * @throws com.sprintstart.sprintstartbackend.upload.model.exceptions.IngestionResponseException
     * when the AI ingestion service rejects the sync request.
     */
    suspend fun ingestRunArtifacts(runId: UUID) {
        val request = withContext(Dispatchers.IO) {
            readTxTemplate.execute { buildSyncRequest(runId) }
        } ?: return

        logger.info(
            "Dispatching AI sync for run {}: {} to ingest, {} to deindex",
            runId,
            request.artifactsToIngest.size,
            request.artifactsToDeindex.size,
        )
        artifactIngestionClient.ingest(request)
        logger.info("AI sync confirmed for run {}", runId)
    }

    private fun buildSyncRequest(runId: UUID): RunArtifactsAiSyncRequest? {
        val run = ingestionRunRepository
            .findWithArtifactIdsToDeindexById(runId)
            .getOrElse { throw IngestionRunNotFoundException(runId) }

        val artifactsToIngest = artifactRepository.findAllByIngestionRunId(runId)
        val artifactsToDeindex = run.artifactIdsToDeindex

        if (artifactsToIngest.isEmpty() && artifactsToDeindex.isEmpty()) {
            logger.info("Run {} has nothing for AI to sync, skipping", runId)
            return null
        }

        return RunArtifactsAiSyncRequest(
            artifactsToIngest = artifactsToIngest.map { artifactAiMapper.toIngestRequest(it) },
            artifactsToDeindex = run.artifactIdsToDeindex,
        )
    }
}
