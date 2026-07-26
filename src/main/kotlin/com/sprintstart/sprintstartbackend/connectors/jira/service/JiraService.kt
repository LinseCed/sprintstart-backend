package com.sprintstart.sprintstartbackend.connectors.jira.service

import com.sprintstart.sprintstartbackend.connectors.jira.JiraClient
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.initial.JiraInstanceConnectionCompletedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.initial.JiraInstanceConnectionInitiatedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.initial.JiraInstanceConnectionInitiationFailedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingFailedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingStartedEvent
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
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*

@Service
class JiraService(
    private val credentialsRepository: JiraCredentialsRepository,
    private val instanceRepository: JiraInstanceRepository,
    private val jiraClient: JiraClient,
    private val applicationScope: CoroutineScope,
    private val jiraIssueService: JiraIssueService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Tracked("Connecting Jira Cloud instance if not already connected")
    suspend fun connectInstanceIfNeeded(request: ConnectJiraInstanceRequest): UUID = withContext(Dispatchers.IO) {
        val transactionId = UUID.randomUUID()
        eventPublisher.publishEvent(JiraInstanceConnectionInitiatedEvent(transactionId, request.displayName))

        val instance = instanceRepository.findById(request.url)
        if (instance.isPresent) {
            logger.info("Jira instance already connected: ${request.url}")

            eventPublisher.publishEvent(JiraInstanceConnectionCompletedEvent(transactionId))

            return@withContext if (instance.get().projectIds.contains(request.projectId)) {
                transactionId
            } else {
                instance.get().projectIds.add(request.projectId)
                instanceRepository.save(instance.get())
                transactionId
            }
        }

        connectInstanceIfExists(request, transactionId)

        return@withContext transactionId
    }

    @Tracked("Connecting new Jira Cloud instance")
    suspend fun connectInstanceIfExists(request: ConnectJiraInstanceRequest, transactionId: UUID) {
        val credentials = withContext(Dispatchers.IO) { credentialsRepository.findByUserEmail(request.userEmail) }
        if (credentials == null) {
            eventPublisher.publishEvent(
                JiraInstanceConnectionInitiationFailedEvent(
                    transactionId,
                    "Invalid credentials",
                ),
            )
            throw JiraCredentialNotFoundException(request.userEmail)
        }

        if (!jiraClient.checkInstanceCapabilities(request.url)) {
            eventPublisher.publishEvent(
                JiraInstanceConnectionInitiationFailedEvent(
                    transactionId,
                    "Instance is not available",
                ),
            )
            throw JiraInstanceUnavailableException(request.url)
        }

        return connectInstance(request, credentials, transactionId)
    }

    @Tracked("Starting Jira instance connection process")
    private suspend fun connectInstance(
        request: ConnectJiraInstanceRequest,
        credentials: JiraCredentials,
        transactionId: UUID,
    ) {
        eventPublisher.publishEvent(JiraResourceFetchingStartedEvent(transactionId))

        val projects = runCatching { jiraClient.searchProjects(request.url, credentials) }
            .onFailure {
                eventPublisher.publishEvent(
                    JiraResourceFetchingFailedEvent(transactionId, it.message ?: "Unknown error"),
                )
                throw it
            }.getOrNull() ?: return

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
            jiraIssueService.searchAndIngestAllIssuesOfProjects(
                instance,
                credentials,
                projects.map { it.key },
                transactionId,
            )
        }
    }
}
