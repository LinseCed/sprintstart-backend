package com.sprintstart.sprintstartbackend.connectors.jira.service

import com.sprintstart.sprintstartbackend.connectors.jira.JiraClient
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.ConnectJiraInstanceRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentials
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstance
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraCredentialNotFoundException
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraInstanceUnavailableException
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraCredentialsRepository
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraInstanceRepository
import com.sprintstart.sprintstartbackend.connectors.jira.service.internal.JiraIssueService
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

@Service
class JiraService(
    private val credentialsRepository: JiraCredentialsRepository,
    private val instanceRepository: JiraInstanceRepository,
    private val jiraClient: JiraClient,
    private val applicationScope: CoroutineScope,
    private val jiraIssueService: JiraIssueService,
) {
    @Tracked("Connecting new Jira Cloud instance")
    suspend fun connectInstanceIfExists(request: ConnectJiraInstanceRequest) {
        val credentials = withContext(Dispatchers.IO) {
            credentialsRepository.findByUserEmail(request.userEmail)
        } ?: throw JiraCredentialNotFoundException(request.userEmail)

        if (!jiraClient.checkInstanceCapabilities(request.url)) {
            throw JiraInstanceUnavailableException(request.url)
        }

        return connectInstance(request.displayName, request.url, credentials)
    }

    @Tracked("Starting Jira instance connection process")
    private suspend fun connectInstance(displayName: String, uri: String, credentials: JiraCredentials) {
        val projects = jiraClient.searchProjects(uri, credentials)
        val instance = JiraInstance(displayName, uri)

        withContext(Dispatchers.IO) {
            instanceRepository.save(instance)
        }

        applicationScope.launch {
            jiraIssueService.searchAndIngestAllIssuesOfProjects(uri, credentials, projects.map { it.key })
        }
    }
}
