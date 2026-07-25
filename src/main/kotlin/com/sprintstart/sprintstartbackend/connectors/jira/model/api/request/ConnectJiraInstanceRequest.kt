package com.sprintstart.sprintstartbackend.connectors.jira.model.api.request

import jakarta.validation.constraints.NotBlank

data class ConnectJiraInstanceRequest(
    @NotBlank
    val displayName: String,
    @NotBlank
    val url: String,
    @NotBlank
    val userEmail: String,
    @NotBlank
    val projectId: String,
)
