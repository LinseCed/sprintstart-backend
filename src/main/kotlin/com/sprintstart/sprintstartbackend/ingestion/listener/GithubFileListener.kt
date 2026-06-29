package com.sprintstart.sprintstartbackend.ingestion.listener

import com.sprintstart.sprintstartbackend.github.external.events.files.GithubFileDeletedEvent
import com.sprintstart.sprintstartbackend.github.external.events.files.GithubFileFetchFailedEvent
import com.sprintstart.sprintstartbackend.github.external.events.files.GithubFileFetchedEvent
import com.sprintstart.sprintstartbackend.github.external.events.files.GithubFilesFetchCompletedEvent
import com.sprintstart.sprintstartbackend.github.external.events.files.GithubFilesFetchFailedEvent
import com.sprintstart.sprintstartbackend.ingestion.model.entity.FinishedTypes
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.GithubArtifactFailedMapper
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.GithubArtifactMapper
import com.sprintstart.sprintstartbackend.ingestion.service.ArtifactIngestionService
import com.sprintstart.sprintstartbackend.ingestion.service.GithubFetchingCompletionTracker
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
internal class GithubFileListener(
    private val artifactIngestionService: ArtifactIngestionService,
    private val gitHubFetchingCompletionTracker: GithubFetchingCompletionTracker,
    private val githubArtifactMapper: GithubArtifactMapper,
    private val githubArtifactFailedMapper: GithubArtifactFailedMapper,
) {
    @EventListener
    fun on(
        event: GithubFileFetchedEvent,
    ) {
        artifactIngestionService.ingest(githubArtifactMapper.toCommand(event))
    }

    @EventListener
    fun on(
        event: GithubFilesFetchCompletedEvent,
    ) {
        gitHubFetchingCompletionTracker.markFetchPhaseFinished(
            event.transactionId,
            finishedType = FinishedTypes.FILES,
        )
    }

    @EventListener
    fun on(
        event: GithubFileFetchFailedEvent,
    ) {
        artifactIngestionService.addFailedArtifact(githubArtifactFailedMapper.toCommand(event))
    }

    @EventListener
    fun on(
        event: GithubFilesFetchFailedEvent,
    ) {
        gitHubFetchingCompletionTracker.markFetchPhaseFinished(
            event.transactionId,
            finishedType = FinishedTypes.FILES,
        )
    }

    @EventListener
    fun on(
        event: GithubFileDeletedEvent,
    ) {
        artifactIngestionService.unIngestFileArtifact(event)
    }
}
