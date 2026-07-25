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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class JiraService(
    private val credentialsRepository: JiraCredentialsRepository,
    private val instanceRepository: JiraInstanceRepository,
    private val jiraClient: JiraClient,
    private val applicationScope: CoroutineScope,
    private val jiraIssueService: JiraIssueService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Tracked("Connecting Jira Cloud instance if not already connected")
    suspend fun connectInstanceIfNeeded(request: ConnectJiraInstanceRequest): Unit = withContext(Dispatchers.IO) {
        val instance = instanceRepository.findById(request.url)
        if (instance.isPresent) {
            logger.info("Jira instance already connected: ${request.url}")

            if (instance.get().projectIds.contains(request.projectId)) {
                return@withContext
            } else {
                instance.get().projectIds.add(request.projectId)
                instanceRepository.save(instance.get())
                return@withContext
            }
        }

        return@withContext connectInstanceIfExists(request)
    }

    @Tracked("Connecting new Jira Cloud instance")
    suspend fun connectInstanceIfExists(request: ConnectJiraInstanceRequest) {
        val credentials = withContext(Dispatchers.IO) {
            credentialsRepository.findByUserEmail(request.userEmail)
        } ?: throw JiraCredentialNotFoundException(request.userEmail)

        if (!jiraClient.checkInstanceCapabilities(request.url)) {
            throw JiraInstanceUnavailableException(request.url)
        }

        return connectInstance(request, credentials)
    }

    @Tracked("Starting Jira instance connection process")
    private suspend fun connectInstance(request: ConnectJiraInstanceRequest, credentials: JiraCredentials) {
        val projects = jiraClient.searchProjects(request.url, credentials)
        val instance = JiraInstance(
            instanceUrl = request.url,
            displayName = request.displayName,
            lastUpdate = Instant.now(),
            projectIds = mutableSetOf(request.projectId),
        )

        withContext(Dispatchers.IO) {
            instanceRepository.save(instance)
        }

        applicationScope.launch {
            jiraIssueService.searchAndIngestAllIssuesOfProjects(instance, credentials, projects.map { it.key })
        }
    }
}
