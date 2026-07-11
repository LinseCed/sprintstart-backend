package com.sprintstart.sprintstartbackend.ingestion.listener

import com.sprintstart.sprintstartbackend.github.external.events.initial.GithubRepositoryConnectionInitiatedEvent
import com.sprintstart.sprintstartbackend.github.external.events.initial.GithubRepositoryConnectionInitiationFailedEvent
import com.sprintstart.sprintstartbackend.ingestion.listener.github.GithubRepositoryConnectionListener
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.service.provider.GithubArtifactProviderService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class GithubRepositoryConnectionListenerTest {
    private val githubArtifactProviderService = mockk<GithubArtifactProviderService>()
    private val listener = GithubRepositoryConnectionListener(githubArtifactProviderService)

    @Test
    fun `initiated event starts connected github run`() {
        val runId = UUID.randomUUID()
        every { githubArtifactProviderService.startRun(any(), any(), any(), any()) } just runs

        listener.on(
            GithubRepositoryConnectionInitiatedEvent(
                transactionId = runId,
                owner = "owner",
                name = "repo",
            ),
        )

        verify(exactly = 1) {
            githubArtifactProviderService.startRun(
                transactionId = runId,
                sourceSystem = SourceSystem.GITHUB,
                status = IngestionRunStatus.CONNECTED,
                failureReason = null,
            )
        }
    }

    @Test
    fun `failed initiation event starts failed github run`() {
        val runId = UUID.randomUUID()
        every { githubArtifactProviderService.startRun(any(), any(), any(), any()) } just runs

        listener.on(
            GithubRepositoryConnectionInitiationFailedEvent(
                transactionId = runId,
                owner = "owner",
                name = "repo",
                reason = "Token rejected",
            ),
        )

        verify(exactly = 1) {
            githubArtifactProviderService.startRun(
                transactionId = runId,
                sourceSystem = SourceSystem.GITHUB,
                status = IngestionRunStatus.FAILED,
                failureReason = "Token rejected",
            )
        }
    }
}
