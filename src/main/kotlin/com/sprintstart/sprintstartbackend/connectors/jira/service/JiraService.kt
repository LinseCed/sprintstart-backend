package com.sprintstart.sprintstartbackend.connectors.jira.service

import com.sprintstart.sprintstartbackend.connectors.jira.JiraClient
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.initial.JiraInstanceConnectionCompletedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.initial.JiraInstanceConnectionInitiatedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.initial.JiraInstanceConnectionInitiationFailedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingFailedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingStartedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.ConnectJiraInstanceRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.JiraInstanceDto
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.toDto
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredential
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentialsId
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstance
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstanceConfig
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraCredentialNotFoundException
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraInstanceNotConnectedException
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraInstanceUnavailableException
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraCredentialsRepository
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraInstanceConfigRepository
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
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class JiraService(
    private val credentialsRepository: JiraCredentialsRepository,
    private val instanceRepository: JiraInstanceRepository,
    private val configRepository: JiraInstanceConfigRepository,
    private val jiraClient: JiraClient,
    private val applicationScope: CoroutineScope,
    private val jiraIssueService: JiraIssueService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Retrieves all connected Jira instances as a list of DTOs.
     *
     * @return a list of JiraInstanceDto representing all connected Jira instances.
     */
    @Transactional(readOnly = true)
    @Tracked("Retrieving all connected Jira instances")
    fun getInstances(): List<JiraInstanceDto> =
        instanceRepository.findAll().map { it.toDto() }

    /**
     * Retrieves a list of Jira instances associated with the specified project.
     *
     * @param projectId the unique identifier of the project for which connected Jira instances should be retrieved.
     * @return a list of JiraInstanceDto representing the connected Jira instances associated with the given project.
     */
    @Transactional(readOnly = true)
    @Tracked("Retrieving connected Jira instances for project")
    fun getInstances(projectId: UUID): List<JiraInstanceDto> =
        instanceRepository.findByProjectId(projectId).map { it.toDto() }

    /**
     * Updates the status of a Jira instance identified by its ID.
     *
     * @param instanceId The unique identifier of the Jira instance to be updated.
     * @param newStatus The new status to set for the Jira instance's sourceEnabled property.
     */
    @Tracked("Patching Jira instance status")
    fun patchInstance(instanceId: String, newStatus: Boolean) {
        val instance = instanceRepository.findById(instanceId).orElseThrow {
            throw JiraInstanceNotConnectedException(instanceId)
        }
        instance.sourceEnabled = newStatus
        instanceRepository.save(instance)
    }

    /**
     * Establishes a connection to a Jira Cloud instance if it is not already connected.
     * If the instance is already connected, it ensures the project ID is associated with the instance.
     *
     * @param request The connection request containing information about the Jira Cloud instance,
     *                including its URL, display name, and the project ID to associate with the instance.
     * @return A universally unique identifier (UUID) representing the transaction ID of the connection process.
     */
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

    /**
     * Attempts to connect a Jira Cloud instance if it exists and meets the required conditions.
     *
     * @param request A data object containing the details of the Jira instance connection request, including
     *                user email, token name, and instance URL.
     * @param transactionId A unique identifier for the transaction, used to track the connection process.
     * @throws JiraCredentialNotFoundException if the credentials for the specified user and token name cannot be found.
     * @throws JiraInstanceUnavailableException if the Jira instance is unavailable or does not meet the required capabilities.
     */
    @Tracked("Connecting new Jira Cloud instance")
    suspend fun connectInstanceIfExists(request: ConnectJiraInstanceRequest, transactionId: UUID) {
        val credentials = withContext(Dispatchers.IO) {
            credentialsRepository.findById(
                JiraCredentialsId(
                    request.userEmail,
                    request.tokenName,
                ),
            )
        }

        if (credentials.isEmpty) {
            eventPublisher.publishEvent(
                JiraInstanceConnectionInitiationFailedEvent(
                    transactionId,
                    "Invalid credentials",
                ),
            )
            throw JiraCredentialNotFoundException(request.userEmail, request.tokenName)
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

        return connectInstance(request, credentials.get(), transactionId)
    }

    /**
     * Connects to a Jira instance, fetches its resources, and saves its configuration and projects.
     *
     * @param request The request containing the Jira instance URL, display name, and specific project data.
     * @param credentials The credentials required to authenticate with the Jira instance.
     * @param transactionId A unique identifier for tracking the connection and resource-fetching process.
     */
    @Tracked("Starting Jira instance connection process")
    private suspend fun connectInstance(
        request: ConnectJiraInstanceRequest,
        credentials: JiraCredential,
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
        val config = JiraInstanceConfig(
            instance = instance,
        )

        withContext(Dispatchers.IO) {
            instanceRepository.save(instance)
            configRepository.save(config)
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
