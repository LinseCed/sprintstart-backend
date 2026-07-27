package com.sprintstart.sprintstartbackend.ingestion.listener.github

import com.sprintstart.sprintstartbackend.connectors.github.external.GithubRepositoryApi
import com.sprintstart.sprintstartbackend.connectors.github.external.events.initial.GithubRepositoryConnectionInitiatedEvent
import com.sprintstart.sprintstartbackend.connectors.github.external.events.initial.GithubRepositoryConnectionInitiationFailedEvent
import com.sprintstart.sprintstartbackend.ingestion.external.model.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.entity.IngestionRunStatus
import com.sprintstart.sprintstartbackend.ingestion.service.IngestionRunLifeCycleService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class GithubRepositoryConnectionListener(
    private val ingestionRunLifeCycleService: IngestionRunLifeCycleService,
    private val githubRepositoryApi: GithubRepositoryApi,
) {
    @EventListener
    fun on(
        event: GithubRepositoryConnectionInitiatedEvent,
    ) {
        ingestionRunLifeCycleService
            .startRun(
                transactionId = event.transactionId,
                sourceSystem = SourceSystem.GITHUB,
                status = IngestionRunStatus.CONNECTED,
                owner = event.owner,
                name = event.name,
                repositoryId = resolveRepositoryId(event.owner, event.name),
            )
    }

    @EventListener
    fun on(
        event: GithubRepositoryConnectionInitiationFailedEvent,
    ) {
        ingestionRunLifeCycleService
            .startRun(
                transactionId = event.transactionId,
                sourceSystem = SourceSystem.GITHUB,
                status = IngestionRunStatus.FAILED,
                failureReason = event.reason,
                owner = event.owner,
                name = event.name,
                repositoryId = resolveRepositoryId(event.owner, event.name),
            )
    }

    private fun resolveRepositoryId(
        owner: String,
        name: String,
    ): UUID? = githubRepositoryApi.getRepositoryIdByOwnerAndName(owner, name)
}
