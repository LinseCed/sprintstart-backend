package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.connectors.github.models.ConnectionState
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositorySnapshot
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.ingestion.external.model.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.entity.AiSyncStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRun
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class IngestionSourceStatusServiceTest {
    private val githubRepositoryConnectionRepository = mockk<GithubRepositoryConnectionRepository>()
    private val ingestionRunRepository = mockk<IngestionRunRepository>()
    private val service = IngestionSourceStatusService(githubRepositoryConnectionRepository, ingestionRunRepository)

    @Test
    fun `maps connected repository with its latest run counters and snapshot timestamps`() {
        val repositoryId = UUID.randomUUID()
        val commitsAt = Instant.parse("2026-07-06T10:00:00Z")
        val issuesAt = Instant.parse("2026-07-06T11:00:00Z")
        val prAt = Instant.parse("2026-07-06T12:00:00Z")
        val connection = connection(
            id = repositoryId,
            owner = "SprintStartProject",
            name = "sprintstart-frontend",
            sourceEnabled = true,
            connectionState = ConnectionState.UP_TO_DATE,
            snapshot = mockk<GithubRepositorySnapshot> {
                every { lastCommitsSyncAt } returns commitsAt
                every { lastIssuesSyncAt } returns issuesAt
                every { lastPullRequestsSyncAt } returns prAt
            },
        )
        val run = IngestionRun(
            id = UUID.randomUUID(),
            sourceSystem = SourceSystem.GITHUB,
            repositoryId = repositoryId,
            owner = "SprintStartProject",
            name = "sprintstart-frontend",
            startedAt = Instant.parse("2026-07-06T12:30:00Z"),
            ingestedCount = 42,
            updatedCount = 3,
            deletedCount = 1,
            failedCount = 0,
            status = IngestionRunStatus.COMPLETED,
            aiSyncStatus = AiSyncStatus.SUCCEEDED,
        )
        every { githubRepositoryConnectionRepository.findAll() } returns listOf(connection)
        every { ingestionRunRepository.findFirstByRepositoryIdOrderByStartedAtDesc(repositoryId) } returns run

        val response = service.getStatusPerSourceInstance().single()

        assertThat(response.sourceSystem).isEqualTo(SourceSystem.GITHUB)
        assertThat(response.sourceId).isEqualTo("SprintStartProject/sprintstart-frontend")
        assertThat(response.repositoryId).isEqualTo(repositoryId)
        assertThat(response.owner).isEqualTo("SprintStartProject")
        assertThat(response.name).isEqualTo("sprintstart-frontend")
        assertThat(response.sourceUrl).isEqualTo("https://github.com/SprintStartProject/sprintstart-frontend")
        assertThat(response.status).isEqualTo("CONNECTED")
        assertThat(response.enabled).isTrue()
        assertThat(response.lastRunTime).isEqualTo(run.startedAt)
        assertThat(response.ingestedCount).isEqualTo(42)
        assertThat(response.updatedCount).isEqualTo(3)
        assertThat(response.deletedCount).isEqualTo(1)
        assertThat(response.failedCount).isZero()
        assertThat(response.lastCommitsSyncAt).isEqualTo(commitsAt)
        assertThat(response.lastIssuesSyncAt).isEqualTo(issuesAt)
        assertThat(response.lastPullRequestsSyncAt).isEqualTo(prAt)
    }

    @Test
    fun `reports disabled status and empty counters when repository has no run or snapshot`() {
        val repositoryId = UUID.randomUUID()
        val connection = connection(
            id = repositoryId,
            owner = "owner",
            name = "repo",
            sourceEnabled = false,
            connectionState = ConnectionState.UP_TO_DATE,
            snapshot = null,
        )
        every { githubRepositoryConnectionRepository.findAll() } returns listOf(connection)
        every { ingestionRunRepository.findFirstByRepositoryIdOrderByStartedAtDesc(repositoryId) } returns null

        val response = service.getStatusPerSourceInstance().single()

        assertThat(response.status).isEqualTo("DISABLED")
        assertThat(response.enabled).isFalse()
        assertThat(response.lastRunTime).isNull()
        assertThat(response.ingestedCount).isZero()
        assertThat(response.failedItems).isEmpty()
        assertThat(response.lastCommitsSyncAt).isNull()
        assertThat(response.lastIssuesSyncAt).isNull()
        assertThat(response.lastPullRequestsSyncAt).isNull()
    }

    @Test
    fun `filters by project id when provided`() {
        val projectId = UUID.randomUUID()
        val repositoryId = UUID.randomUUID()
        val connection = connection(
            id = repositoryId,
            owner = "owner",
            name = "repo",
            sourceEnabled = true,
            connectionState = ConnectionState.OUT_OF_DATE,
            snapshot = null,
        )
        every { githubRepositoryConnectionRepository.findAllByProjectId(projectId) } returns listOf(connection)
        every { ingestionRunRepository.findFirstByRepositoryIdOrderByStartedAtDesc(repositoryId) } returns null

        val response = service.getStatusPerSourceInstance(projectId).single()

        assertThat(response.repositoryId).isEqualTo(repositoryId)
        assertThat(response.status).isEqualTo("OUT_OF_DATE")
    }

    private fun connection(
        id: UUID,
        owner: String,
        name: String,
        sourceEnabled: Boolean,
        connectionState: ConnectionState,
        snapshot: GithubRepositorySnapshot?,
    ): GithubRepositoryConnection =
        mockk {
            every { this@mockk.id } returns id
            every { this@mockk.owner } returns owner
            every { this@mockk.name } returns name
            every { this@mockk.sourceEnabled } returns sourceEnabled
            every { this@mockk.connectionState } returns connectionState
            every { this@mockk.snapshot } returns snapshot
        }
}
