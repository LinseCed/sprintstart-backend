package com.sprintstart.sprintstartbackend.canonical.listener

import com.sprintstart.sprintstartbackend.canonical.model.entity.FinishedTypes
import com.sprintstart.sprintstartbackend.canonical.model.mapper.GithubArtifactMapper
import com.sprintstart.sprintstartbackend.canonical.service.ArtifactIngestionService
import com.sprintstart.sprintstartbackend.canonical.service.GithubFetchingCompletionTracker
import com.sprintstart.sprintstartbackend.github.external.events.pullrequests.GithubPullRequestFetchedEvent
import com.sprintstart.sprintstartbackend.github.external.events.pullrequests.GithubPullRequestsFetchCompletedEvent
import com.sprintstart.sprintstartbackend.github.external.events.pullrequests.GithubPullRequestsFetchFailedEvent
import org.springframework.context.event.EventListener
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
internal class GithubPullRequestListener(
    private val artifactIngestionService: ArtifactIngestionService,
    private val gitHubFetchingCompletionTracker: GithubFetchingCompletionTracker,
    private val githubArtifactMapper: GithubArtifactMapper,
) {
    @EventListener
    fun on(
        event: GithubPullRequestFetchedEvent,
    ) {
        artifactIngestionService.ingest(githubArtifactMapper.toCommand(event))
    }

    @EventListener
    fun on(
        event: GithubPullRequestsFetchCompletedEvent,
    ) {
        gitHubFetchingCompletionTracker.markFetchPhaseFinished(
            event.transactionId,
            finishedType = FinishedTypes.PULL_REQUESTS,
        )
    }

    @EventListener
    fun on(
        event: GithubPullRequestsFetchFailedEvent,
    ) {
        gitHubFetchingCompletionTracker.markFetchPhaseFinished(
            event.transactionId,
            finishedType = FinishedTypes.PULL_REQUESTS,
        )
    }
}
