package com.sprintstart.sprintstartbackend.ingestion.listener

import com.sprintstart.sprintstartbackend.github.external.events.GithubRepositoryResourcesFetchingStartedEvent
import com.sprintstart.sprintstartbackend.ingestion.listener.github.GithubRepositoryResourcesListener
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.service.provider.GithubArtifactProviderService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class GithubRepositoryResourcesListenerTest {
    private val githubArtifactProviderService = mockk<GithubArtifactProviderService>()
    private val listener = GithubRepositoryResourcesListener(githubArtifactProviderService)

    @Test
    fun `fetching started event marks run as running`() {
        val runId = UUID.randomUUID()
        every { githubArtifactProviderService.updateRunStatus(any(), any()) } just runs

        listener.on(
            GithubRepositoryResourcesFetchingStartedEvent(
                transactionId = runId,
                owner = "owner",
                name = "repo",
            ),
        )

        verify(exactly = 1) {
            githubArtifactProviderService.updateRunStatus(
                transactionId = runId,
                status = IngestionRunStatus.RUNNING,
            )
        }
    }
}
