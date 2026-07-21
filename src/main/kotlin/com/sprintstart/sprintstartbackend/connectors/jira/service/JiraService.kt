package com.sprintstart.sprintstartbackend.connectors.jira.service

import com.sprintstart.sprintstartbackend.connectors.jira.JiraClient
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.ConnectJiraInstanceRequest
import org.springframework.stereotype.Service

@Service
class JiraService(
    private val jiraClient: JiraClient,
) {
    fun connectInstance(request: ConnectJiraInstanceRequest) {
        val issues = jiraClient.getIssues()
    }
}
