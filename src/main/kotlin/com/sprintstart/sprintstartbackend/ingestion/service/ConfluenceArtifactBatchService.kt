package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.connectors.confluence.external.ingestion.ConfluenceArtifactBatchCommand
import com.sprintstart.sprintstartbackend.connectors.confluence.external.ingestion.ConfluenceArtifactBatchResult
import com.sprintstart.sprintstartbackend.connectors.confluence.external.ingestion.ConfluenceArtifactFailureStage
import com.sprintstart.sprintstartbackend.connectors.confluence.external.ingestion.ConfluencePageArtifactCommand
import com.sprintstart.sprintstartbackend.connectors.confluence.external.ingestion.ConfluencePageArtifactFailure
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/** Persists a Confluence batch while isolating page-local integrity failures. */
@Service
internal class ConfluenceArtifactBatchService(
    private val itemPersistenceService: ConfluenceArtifactItemPersistenceService,
    private val failurePersistenceService: ConfluenceArtifactFailurePersistenceService,
) {
    fun persist(command: ConfluenceArtifactBatchCommand): ConfluenceArtifactBatchResult {
        val sourceIds = command.artifacts.map { artifact -> artifact.sourceId }
        require(sourceIds.distinct().size == command.artifacts.size) {
            "Confluence ingestion batch contains duplicate source identities"
        }

        val attempts = command.artifacts.map { artifact -> attempt(command, artifact) }
        val persistenceFailures = attempts.mapNotNull { it.failure }

        (command.failures + persistenceFailures).forEach { failure ->
            failurePersistenceService.record(command.runId, failure)
        }
        return ConfluenceArtifactBatchResult(
            created = attempts.count { it.result == ConfluenceArtifactItemPersistenceResult.CREATED },
            updated = attempts.count { it.result == ConfluenceArtifactItemPersistenceResult.UPDATED },
            unchanged = attempts.count { it.result == ConfluenceArtifactItemPersistenceResult.UNCHANGED },
            failed = command.failures.size + persistenceFailures.size,
            persistenceFailures = persistenceFailures,
        )
    }

    private fun attempt(command: ConfluenceArtifactBatchCommand, artifact: ConfluencePageArtifactCommand): Attempt =
        try {
            Attempt(result = itemPersistenceService.persist(command.runId, command.projectId, artifact))
        } catch (@Suppress("SwallowedException") exception: DataIntegrityViolationException) {
            Attempt(
                failure = ConfluencePageArtifactFailure(
                    pageId = artifact.metadata.pageId,
                    sourceUrl = artifact.sourceUrl,
                    stage = ConfluenceArtifactFailureStage.PERSISTENCE,
                    reason = PERSISTENCE_FAILURE_REASON,
                ),
            )
        }

    /** What became of one page: it was stored, or storing it failed. */
    private data class Attempt(
        val result: ConfluenceArtifactItemPersistenceResult? = null,
        val failure: ConfluencePageArtifactFailure? = null,
    )

    private companion object {
        const val PERSISTENCE_FAILURE_REASON = "Confluence page artifact could not be persisted"
    }
}
