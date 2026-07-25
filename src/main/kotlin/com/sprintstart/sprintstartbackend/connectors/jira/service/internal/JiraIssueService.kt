package com.sprintstart.sprintstartbackend.connectors.jira.service.internal

import com.sprintstart.sprintstartbackend.connectors.jira.JiraClient
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentials
import org.springframework.stereotype.Service

@Service
class JiraIssueService(
    private val jiraClient: JiraClient,
) {
    suspend fun searchAndIngestAllIssuesOfProjects(uri: String, credentials: JiraCredentials, projectId: List<String>) {
        projectId.forEach {
            searchAndIngestAllIssuesOfProject(uri, credentials, it)
        }
    }

    suspend fun searchAndIngestAllIssuesOfProject(uri: String, credentials: JiraCredentials, projectId: String) {
        val issues = jiraClient.searchIssues(uri, credentials, "project=$projectId")
        // TODO: Process issues (store least amount of metadata possible)
        // TODO: Ingest issues
    }
}
