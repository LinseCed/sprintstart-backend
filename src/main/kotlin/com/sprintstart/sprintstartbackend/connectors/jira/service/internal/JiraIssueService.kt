package com.sprintstart.sprintstartbackend.connectors.jira.service.internal

import com.sprintstart.sprintstartbackend.connectors.ConnectionState
import com.sprintstart.sprintstartbackend.connectors.jira.JiraClient
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraIssueFetchedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingCompleteEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingFailedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.JiraIssueResponse
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredential
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentialsId
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstance
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraIssue
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraCredentialNotFoundException
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraCredentialsRepository
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraInstanceRepository
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraIssueRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JiraIssueService(
    private val issueRepository: JiraIssueRepository,
    private val instanceRepository: JiraInstanceRepository,
    private val jiraClient: JiraClient,
    private val eventPublisher: ApplicationEventPublisher,
    private val credentialsRepository: JiraCredentialsRepository,
) {
    /**
     * Fetches and ingests all issues from a Jira instance for the specified list of project IDs.
     *
     * @param instance The Jira instance from which issues need to be fetched.
     * @param credentials The credentials required to access the Jira instance.
     * @param projectId A list of project IDs for which issues need to be fetched and ingested.
     * @param transactionId A unique identifier for the transaction or operation being executed.
     */
    @Tracked("Fetching & ingesting all jira instance's issues of a list of projects")
    suspend fun searchAndIngestAllIssuesOfProjects(
        instance: JiraInstance,
        credentials: JiraCredential,
        projectId: List<String>,
        transactionId: UUID,
    ) {
        projectId.forEach {
            searchAndIngestAllIssuesOfProject(instance, credentials, it, transactionId)
        }
    }

    /**
     * Fetches and processes all issues for a given Jira project and ingests them into the system.
     *
     * @param instance The Jira instance from which to fetch issues.
     * @param credentials The credentials required to authenticate against the Jira instance.
     * @param projectId The ID of the Jira project whose issues need to be fetched.
     * @param transactionId A unique transaction identifier used for tracking the operation.
     */
    @Tracked("Fetching & ingesting all jira instance's issues of a single project")
    suspend fun searchAndIngestAllIssuesOfProject(
        instance: JiraInstance,
        credentials: JiraCredential,
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

    /**
     * Checks a Jira instance for updates and modifies its connection status accordingly without applying the updates.
     *
     * If no new or updated issues are found, the instance status is updated to UP_TO_DATE.
     * Otherwise, the instance status is updated to OUT_OF_DATE.
     *
     * @param instance The JiraInstance object to be checked for updates.
     * @param transactionId A unique identifier for the transaction associated with the update-check process.
     */
    @Tracked("Fetching a Jira instance for updates without applying the updates")
    suspend fun checkInstanceForUpdates(instance: JiraInstance, transactionId: UUID) = withContext(Dispatchers.IO) {
        instance.status = ConnectionState.UPDATING
        instanceRepository.save(instance)

        val newAndUpdatedIssues = fetchNewAndUpdatedIssuesFromInstance(instance, transactionId)

        if (newAndUpdatedIssues.isEmpty()) {
            instance.status = ConnectionState.UP_TO_DATE
            instanceRepository.save(instance)
            return@withContext
        }

        instance.status = ConnectionState.OUT_OF_DATE
        instanceRepository.save(instance)
    }

    /**
     * Updates the specified Jira instance by fetching new and updated issues and processing them.
     *
     * This method changes the status of the instance to UPDATING during the process of fetching
     * and processing updates. Once the updates are completed, the instance status is set to UP_TO_DATE.
     *
     * @param instance The Jira instance to be updated.
     * @param transactionId A unique identifier for the ongoing transaction.
     */
    @Tracked("Fetching & applying updates to a Jira instance")
    suspend fun updateInstance(instance: JiraInstance, transactionId: UUID) = withContext(Dispatchers.IO) {
        instance.status = ConnectionState.UPDATING
        instanceRepository.save(instance)

        val newAndUpdatedIssues = fetchNewAndUpdatedIssuesFromInstance(instance, transactionId)

        if (newAndUpdatedIssues.isNotEmpty()) {
            processAndIngestIssues(instance, newAndUpdatedIssues, transactionId)
        }
        instance.status = ConnectionState.UP_TO_DATE
        instanceRepository.save(instance)
    }

    /**
     * Fetches new and updated issues from a specified Jira instance based on the given JQL query.
     *
     * @param instance The Jira instance containing details such as URL, credentials, and last update timestamp.
     * @param transactionId A unique identifier for the ongoing transaction, used for tracking and logging.
     * @return A list of JiraIssueResponse objects representing the new or updated issues fetched from the Jira instance.
     * If no issues are found or an error occurs, an empty list is returned.
     * @throws JiraCredentialNotFoundException if the credentials for the specified Jira instance are not found.
     */
    private suspend fun fetchNewAndUpdatedIssuesFromInstance(
        instance: JiraInstance,
        transactionId: UUID,
    ): List<JiraIssueResponse> {
        val jql =
            "project = \"MYPROJ\" AND (created >= \"${instance.lastUpdate}\" OR updated >= \"${instance.lastUpdate}\")"
        val credentials = withContext(Dispatchers.IO) {
            credentialsRepository
                .findById(
                    JiraCredentialsId(
                        instance.updateCredentialUserEmail,
                        instance.updateCredentialName,
                    ),
                )
        }.orElseThrow {
            eventPublisher.publishEvent(JiraResourceFetchingFailedEvent(transactionId, "Invalid credentials"))
            JiraCredentialNotFoundException(instance.updateCredentialUserEmail, instance.updateCredentialName)
        }

        return runCatching {
            jiraClient.searchIssues(instance.instanceUrl, credentials, jql)
        }.onFailure {
            eventPublisher.publishEvent(JiraResourceFetchingFailedEvent(transactionId, it.message ?: "Unknown error"))
            throw it
        }.getOrNull() ?: emptyList()
    }

    /**
     * Processes and ingests a list of Jira issues from a specified Jira instance.
     *
     * @param instance The Jira instance from which issues are being processed and ingested.
     * @param issues A list of Jira issues, represented as JiraIssueResponse objects, to be processed and ingested.
     * @param transactionId A unique identifier for the transaction or operation being executed.
     */
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

    /**
     * Publishes an event indicating that a Jira issue has been fetched.
     *
     * @param issue The Jira issue to be ingested, represented as a JiraIssueResponse.
     * @param instance The Jira instance from which the issue was fetched.
     * @param transactionId A unique identifier for the operation or transaction.
     */
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
