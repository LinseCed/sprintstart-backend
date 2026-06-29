package com.sprintstart.sprintstartbackend.canonical.listener

import com.sprintstart.sprintstartbackend.canonical.model.entity.FinishedTypes
import com.sprintstart.sprintstartbackend.canonical.model.mapper.GithubArtifactMapper
import com.sprintstart.sprintstartbackend.canonical.service.ArtifactIngestionService
import com.sprintstart.sprintstartbackend.canonical.service.GithubFetchingCompletionTracker
import com.sprintstart.sprintstartbackend.github.external.events.issues.GithubIssueFetchedEvent
import com.sprintstart.sprintstartbackend.github.external.events.issues.GithubIssuesFetchCompletedEvent
import com.sprintstart.sprintstartbackend.github.external.events.issues.GithubIssuesFetchFailedEvent
import org.springframework.context.event.EventListener
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class GithubIssueListener(
    private val artifactIngestionService: ArtifactIngestionService,
    private val gitHubFetchingCompletionTracker: GithubFetchingCompletionTracker,
    private val githubArtifactMapper: GithubArtifactMapper,
) {
    @EventListener
    fun on(
        event: GithubIssueFetchedEvent,
    ) {
        artifactIngestionService.ingest(githubArtifactMapper.toCommand(event))
    }

    @EventListener
    fun on(
        event: GithubIssuesFetchCompletedEvent,
    ) {
        gitHubFetchingCompletionTracker.markFetchPhaseFinished(
            event.transactionId,
            finishedType = FinishedTypes.ISSUES,
        )
    }

    @EventListener
    fun on(
        event: GithubIssuesFetchFailedEvent,
    ) {
        gitHubFetchingCompletionTracker.markFetchPhaseFinished(
            event.transactionId,
            finishedType = FinishedTypes.ISSUES,
        )
    }
}
