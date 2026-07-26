package com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.credentials

import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentials

internal data class JiraCredentialsDto(
    val userEmail: String,
    val displayName: String,
    val accessToken: String,
)

internal fun JiraCredentials.toDto() = JiraCredentialsDto(
    userEmail = this.id.userEmail,
    displayName = this.id.name,
    accessToken = this.authToken,
)
