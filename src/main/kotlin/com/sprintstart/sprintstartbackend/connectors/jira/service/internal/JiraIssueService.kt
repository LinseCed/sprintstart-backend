package com.sprintstart.sprintstartbackend.connectors.jira.service.internal

import com.sprintstart.sprintstartbackend.connectors.jira.JiraClient
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraIssueFetchedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingCompleteEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingFailedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.JiraIssueResponse
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentials
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstance
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraIssue
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraIssueRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JiraIssueService(
    private val issueRepository: JiraIssueRepository,
    private val jiraClient: JiraClient,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Tracked("Fetching & ingesting all jira instance's issues of a list of projects")
    suspend fun searchAndIngestAllIssuesOfProjects(
        instance: JiraInstance,
        credentials: JiraCredentials,
        projectId: List<String>,
        transactionId: UUID,
    ) {
        projectId.forEach {
            searchAndIngestAllIssuesOfProject(instance, credentials, it, transactionId)
        }
    }

    @Tracked("Fetching & ingesting all jira instance's issues of a single project")
    suspend fun searchAndIngestAllIssuesOfProject(
        instance: JiraInstance,
        credentials: JiraCredentials,
        projectId: String,
        transactionId: UUID,
    ) {
        val issues = runCatching {
            jiraClient.searchIssues(
                instance.instanceUrl,
                credentials,
                "project=$projectId",
            )
        }.onFailure {
            eventPublisher.publishEvent(JiraResourceFetchingFailedEvent(transactionId, it.message ?: "Unknown error"))
            throw it
        }.getOrNull() ?: return

        if (issues.isEmpty()) return

        processAndIngestIssues(instance, issues, transactionId)
    }

    private suspend fun processAndIngestIssues(
        instance: JiraInstance,
        issues: List<JiraIssueResponse>,
        transactionId: UUID,
    ) {
        issues
            .map { JiraIssue(it.id, instance) }
            .forEach { issueRepository.save(it) }

        issues.forEach { ingestIssue(it, instance, transactionId) }

        eventPublisher.publishEvent(JiraResourceFetchingCompleteEvent(transactionId))
    }

    private suspend fun ingestIssue(issue: JiraIssueResponse, instance: JiraInstance, transactionId: UUID) {
        val event = JiraIssueFetchedEvent(
            transactionId,
            instance.instanceUrl,
            instance.instanceUrl,
            issue,
        )
        eventPublisher.publishEvent(event)
    }
}
