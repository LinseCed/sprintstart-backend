package com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions

data class JiraInstanceUnavailableException(
    val url: String,
) : RuntimeException("Jira instance at '$url' is unavailable.")
