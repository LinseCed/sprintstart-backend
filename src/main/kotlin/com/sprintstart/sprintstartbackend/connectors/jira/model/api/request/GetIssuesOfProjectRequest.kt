package com.sprintstart.sprintstartbackend.connectors.jira.model.api.request

data class GetIssuesOfProjectRequest(
    val jql: String,
    val maxResults: Int,
)
