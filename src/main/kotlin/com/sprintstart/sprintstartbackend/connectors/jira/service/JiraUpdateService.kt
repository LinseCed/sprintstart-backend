package com.sprintstart.sprintstartbackend.connectors.jira.service

import com.sprintstart.sprintstartbackend.connectors.jira.external.events.update.JiraInstanceUpdateFailedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.external.events.update.JiraInstanceUpdateStartedEvent
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.UpdateJiraInstanceResponse
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstance
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraInstanceNotConnectedException
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraInstanceRepository
import com.sprintstart.sprintstartbackend.connectors.jira.service.internal.JiraIssueService
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JiraUpdateService(
    private val instanceRepository: JiraInstanceRepository,
    private val issueService: JiraIssueService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun updateAllJiraInstances() {
        instanceRepository.findAll().forEach { instance ->
            updateJiraInstance(instance.instanceUrl, true)
        }
    }

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

        if (performUpdate) {
            issueService.updateJiraIssues()
        } else {
            issueService.checkOriginForUpdates()
        }

        return UpdateJiraInstanceResponse(transactionId)
    }
}
