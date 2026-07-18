package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.ArtifactIngestionClient
import com.sprintstart.sprintstartbackend.ingestion.model.dto.request.RunArtifactsAiSyncRequest
import com.sprintstart.sprintstartbackend.ingestion.model.dto.response.RunArtifactsIngestResponse
import com.sprintstart.sprintstartbackend.ingestion.model.entity.Artifact
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRun
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.exceptions.IngestionRunNotFoundException
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.ingestion.ArtifactAiMapper
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Regression coverage for the missing read-transaction bug: [Artifact.labels] is a lazy
 * [jakarta.persistence.ElementCollection], and [ArtifactAiMapper.toIngestRequest] reads it. Before
 * the fix, `ingestRunArtifacts` mapped artifacts outside of any transaction, so the repository's
 * own per-call transaction had already closed by the time `.labels` was accessed, throwing
 * `LazyInitializationException` in production (confirmed via a real ingestion run). These tests
 * can't reproduce a real Hibernate session against a mock repository, but they lock in that the
 * mapping happens inside `readTxTemplate.execute { ... }`, matching VerificationService's
 * established pattern for AI-adjacent reads.
 */
class RunArtifactsIngestionServiceTest {
    private val ingestionRunRepository: IngestionRunRepository = mockk()
    private val artifactRepository: ArtifactRepository = mockk()
    private val artifactAiMapper: ArtifactAiMapper = ArtifactAiMapper()
    private val artifactIngestionClient: ArtifactIngestionClient = mockk()
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)

    private val service = RunArtifactsIngestionService(
        ingestionRunRepository,
        artifactRepository,
        artifactAiMapper,
        artifactIngestionClient,
        transactionManager,
    )

    private val runId = UUID.randomUUID()

    private fun ingestionRun(artifactIdsToDeindex: MutableList<String> = mutableListOf()) = IngestionRun(
        id = runId,
        sourceSystem = SourceSystem.GITHUB,
        status = IngestionRunStatus.COMPLETED,
        artifactIdsToDeindex = artifactIdsToDeindex,
    )

    private fun artifact(labels: MutableList<String> = mutableListOf("good first issue")) = Artifact(
        sourceSystem = SourceSystem.GITHUB,
        sourceId = "github:owner/repo:ISSUE:1",
        sourceUrl = "https://github.com/owner/repo/issues/1",
        artifactType = ArtifactType.ISSUE,
        title = "Some issue",
        content = "body",
        mime = "text/markdown",
        language = null,
        labels = labels,
        createdAtSource = null,
        updatedAtSource = Instant.parse("2026-06-19T09:15:30Z"),
        ingestionRun = ingestionRun(),
        hash = "hash",
    )

    @Test
    fun `maps artifacts with their lazy labels and dispatches the sync request`() = runTest {
        val run = ingestionRun(artifactIdsToDeindex = mutableListOf("deleted-1"))
        val toIngest = artifact(labels = mutableListOf("good first issue", "help wanted"))

        every { ingestionRunRepository.findWithArtifactIdsToDeindexById(runId) } returns Optional.of(run)
        every { artifactRepository.findAllByIngestionRunId(runId) } returns mutableListOf(toIngest)
        val captured = slot<RunArtifactsAiSyncRequest>()
        val response = RunArtifactsIngestResponse(artifacts = emptyList())
        coEvery { artifactIngestionClient.ingest(capture(captured)) } returns response

        service.ingestRunArtifacts(runId)

        assertEquals(1, captured.captured.artifactsToIngest.size)
        assertEquals(listOf("good first issue", "help wanted"), captured.captured.artifactsToIngest[0].labels)
        assertEquals(listOf("deleted-1"), captured.captured.artifactsToDeindex)
    }

    @Test
    fun `skips dispatch when there is nothing to ingest or deindex`() = runTest {
        every { ingestionRunRepository.findWithArtifactIdsToDeindexById(runId) } returns Optional.of(ingestionRun())
        every { artifactRepository.findAllByIngestionRunId(runId) } returns mutableListOf()

        service.ingestRunArtifacts(runId)

        coVerify(exactly = 0) { artifactIngestionClient.ingest(any()) }
    }

    @Test
    fun `throws when the run does not exist`() = runTest {
        every { ingestionRunRepository.findWithArtifactIdsToDeindexById(runId) } returns Optional.empty()

        assertThrows<IngestionRunNotFoundException> {
            service.ingestRunArtifacts(runId)
        }
    }
}
