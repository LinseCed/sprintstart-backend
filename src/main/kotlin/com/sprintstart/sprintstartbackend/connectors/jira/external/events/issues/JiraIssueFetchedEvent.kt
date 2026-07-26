package com.sprintstart.sprintstartbackend.connectors.jira.external.events.issues

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.JiraIssueResponse
import java.util.UUID

data class JiraIssueFetchedEvent(
    val transactionId: UUID,
    val instanceId: String,
    val instanceUrl: String,
    val issue: JiraIssueResponse,
)
