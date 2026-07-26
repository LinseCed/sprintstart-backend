package com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials

data class DeleteJiraCredentialRequest(
    val userEmail: String,
    val tokenName: String,
)
