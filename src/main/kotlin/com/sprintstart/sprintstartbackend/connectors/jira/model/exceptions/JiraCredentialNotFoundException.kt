package com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions

data class JiraCredentialNotFoundException(
    val userEmail: String,
) : RuntimeException("Jira credentials for user '$userEmail' not found.")

