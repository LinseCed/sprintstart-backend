package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.model.entity.AiSyncStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactAiSyncState
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRun
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A run's `aiSyncStatus` is a roll-up of its artifacts' sync state now that syncing happens per
 * artifact rather than once per run.
 */
class IngestionRunAiSyncStatusServiceTest {
    private val ingestionRunRepository: IngestionRunRepository = mockk()
    private val artifactRepository: ArtifactRepository = mockk()

    private val service = IngestionRunAiSyncStatusService(ingestionRunRepository, artifactRepository)

    private val runId: UUID = UUID.randomUUID()

    private fun run() = IngestionRun(
        id = runId,
        sourceSystem = SourceSystem.GITHUB,
        status = IngestionRunStatus.COMPLETED,
        aiSyncStatus = AiSyncStatus.PENDING,
    )

    private fun stubCounts(pending: Long, failed: Long, synced: Long) {
        every {
            artifactRepository.countByAiSyncRunIdAndAiSyncState(runId, ArtifactAiSyncState.PENDING)
        } returns pending
        every {
            artifactRepository.countByAiSyncRunIdAndAiSyncState(runId, ArtifactAiSyncState.FAILED)
        } returns failed
        every {
            artifactRepository.countByAiSyncRunIdAndAiSyncState(runId, ArtifactAiSyncState.SYNCED)
        } returns synced
        every {
            artifactRepository.findAllByAiSyncRunIdAndAiSyncState(runId, ArtifactAiSyncState.FAILED)
        } returns emptyList()
    }

    @Test
    fun `reports succeeded only once every artifact is indexed`() {
        val run = run()
        every { ingestionRunRepository.findById(runId) } returns Optional.of(run)
        stubCounts(pending = 0, failed = 0, synced = 4)

        service.recompute(runId)

        assertEquals(AiSyncStatus.SUCCEEDED, run.aiSyncStatus)
        assertNull(run.aiSyncFailureReason)
    }

    @Test
    fun `stays pending while anything is still owed, even alongside a failure`() {
        val run = run()
        every { ingestionRunRepository.findById(runId) } returns Optional.of(run)
        stubCounts(pending = 1, failed = 1, synced = 2)

        service.recompute(runId)

        // A parked failure can still be picked up again by a later retry, so "owed" outranks
        // "gave up" -- reporting FAILED here would be premature.
        assertEquals(AiSyncStatus.PENDING, run.aiSyncStatus)
    }

    @Test
    fun `reports failed with a reason once nothing is left to retry`() {
        val run = run()
        every { ingestionRunRepository.findById(runId) } returns Optional.of(run)
        stubCounts(pending = 0, failed = 2, synced = 1)

        service.recompute(runId)

        assertEquals(AiSyncStatus.FAILED, run.aiSyncStatus)
        assertNotNull(run.aiSyncFailureReason)
    }

    @Test
    fun `leaves a run that touched no artifacts alone`() {
        val run = run().apply { aiSyncStatus = AiSyncStatus.NOT_APPLICABLE }
        every { ingestionRunRepository.findById(runId) } returns Optional.of(run)
        stubCounts(pending = 0, failed = 0, synced = 0)

        service.recompute(runId)

        assertEquals(AiSyncStatus.NOT_APPLICABLE, run.aiSyncStatus)
    }
}
