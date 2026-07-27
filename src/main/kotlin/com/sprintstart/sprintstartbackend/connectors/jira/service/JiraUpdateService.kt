package com.sprintstart.sprintstartbackend.connectors.jira.service

import com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues.JiraResourceFetchingStartedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.update.JiraInstanceUpdateFailedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.update.JiraInstanceUpdateStartedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.UpdateJiraInstanceResponse
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstance
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraInstanceNotConnectedException
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraInstanceRepository
import com.sprintstart.sprintstartbackend.connectors.jira.service.internal.JiraIssueService
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JiraUpdateService(
    private val instanceRepository: JiraInstanceRepository,
    private val issueService: JiraIssueService,
    private val eventPublisher: ApplicationEventPublisher,
    private val applicationScope: CoroutineScope,
) {
    /**
     * Updates all Jira instances by fetching them from the repository and invoking an update operation
     * for each instance. The update operation is performed via the `updateJiraInstance` method.
     *
     * @return a list of responses, where each entry corresponds to the result of updating a specific Jira instance.
     */
    @Tracked("Updating all Jira instances")
    fun updateAllJiraInstances(): List<UpdateJiraInstanceResponse> {
        return instanceRepository.findAll().map { instance ->
            updateJiraInstance(instance.instanceUrl, true)
        }
    }

    /**
     * Updates the specified Jira instance by either checking for updates or performing a full update operation.
     * This method triggers the appropriate background job based on the provided `performUpdate` flag.
     *
     * The process includes publishing events for the update lifecycle (e.g., start, failure), handling exceptions
     * related to the instance's connectivity, and managing asynchronous operations.
     *
     * @param instanceUrl The URL of the Jira instance to be updated.
     * @param performUpdate A flag indicating whether to perform a full update (true) or just check for updates (false).
     * @return An instance of `UpdateJiraInstanceResponse`, containing the transaction ID associated with the update process.
     * @throws JiraInstanceNotConnectedException If the Jira instance is not connected or cannot be found in the repository.
     */
    @Tracked("Updating a Jira instance")
    fun updateJiraInstance(instanceUrl: String, performUpdate: Boolean): UpdateJiraInstanceResponse {
        val transactionId = UUID.randomUUID()

        eventPublisher.publishEvent(JiraInstanceUpdateStartedEvent(transactionId, instanceUrl))

        val instance = runCatching {
            instanceRepository.findById(instanceUrl).orElseThrow {
                throw JiraInstanceNotConnectedException(instanceUrl)
            }
        }.onFailure { e ->
            eventPublisher.publishEvent(
                JiraInstanceUpdateFailedEvent(
                    transactionId,
                    instanceUrl,
                    e.message ?: "Unknown error",
                ),
            )
            throw e
        }.getOrNull() ?: return UpdateJiraInstanceResponse(transactionId)

        eventPublisher.publishEvent(JiraResourceFetchingStartedEvent(UUID.randomUUID()))

        // Launch background update job
        applicationScope.launch {
            if (performUpdate) {
                issueService.updateInstance(instance, transactionId)
            } else {
                issueService.checkInstanceForUpdates(instance, transactionId)
            }
        }

        return UpdateJiraInstanceResponse(transactionId)
    }
}
