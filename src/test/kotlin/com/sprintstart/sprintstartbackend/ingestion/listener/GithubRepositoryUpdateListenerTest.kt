package com.sprintstart.sprintstartbackend.ingestion.listener

import com.sprintstart.sprintstartbackend.connectors.github.external.events.update.GithubRepositoryUpdateFailedEvent
import com.sprintstart.sprintstartbackend.connectors.github.external.events.update.GithubRepositoryUpdateStartedEvent
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.ingestion.external.model.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.listener.github.GithubRepositoryUpdateListener
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.service.IngestionRunLifeCycleService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class GithubRepositoryUpdateListenerTest {
    private val ingestionRunLifeCycleService = mockk<IngestionRunLifeCycleService>()
    private val githubRepositoryConnectionRepository = mockk<GithubRepositoryConnectionRepository>()
    private val listener =
        GithubRepositoryUpdateListener(ingestionRunLifeCycleService, githubRepositoryConnectionRepository)

    @Test
    fun `update started event starts connected github run with resolved repository metadata`() {
        val runId = UUID.randomUUID()
        val repositoryId = UUID.randomUUID()
        every { ingestionRunLifeCycleService.startRun(any(), any(), any(), any(), any(), any(), any()) } just runs
        every { githubRepositoryConnectionRepository.findByOwnerAndName("owner", "repo") } returns
            mockk<GithubRepositoryConnection> { every { id } returns repositoryId }

        listener.on(
            GithubRepositoryUpdateStartedEvent(
                transactionId = runId,
                owner = "owner",
                name = "repo",
            ),
        )

        verify(exactly = 1) {
            ingestionRunLifeCycleService.startRun(
                transactionId = runId,
                sourceSystem = SourceSystem.GITHUB,
                status = IngestionRunStatus.CONNECTED,
                owner = "owner",
                name = "repo",
                repositoryId = repositoryId,
            )
        }
    }

    @Test
    fun `update failed event starts failed github run with failure reason and repository metadata`() {
        val runId = UUID.randomUUID()
        val repositoryId = UUID.randomUUID()
        every { ingestionRunLifeCycleService.startRun(any(), any(), any(), any(), any(), any(), any()) } just runs
        every { githubRepositoryConnectionRepository.findByOwnerAndName("owner", "repo") } returns
            mockk<GithubRepositoryConnection> { every { id } returns repositoryId }

        listener.on(
            GithubRepositoryUpdateFailedEvent(
                transactionId = runId,
                owner = "owner",
                name = "repo",
                reason = "Snapshot missing",
            ),
        )

        verify(exactly = 1) {
            ingestionRunLifeCycleService.startRun(
                transactionId = runId,
                sourceSystem = SourceSystem.GITHUB,
                status = IngestionRunStatus.FAILED,
                failureReason = "Snapshot missing",
                owner = "owner",
                name = "repo",
                repositoryId = repositoryId,
            )
        }
    }
}
