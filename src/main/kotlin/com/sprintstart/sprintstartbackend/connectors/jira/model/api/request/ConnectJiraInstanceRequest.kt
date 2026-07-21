package com.sprintstart.sprintstartbackend.connectors.jira.model.api.request

import jakarta.validation.constraints.NotBlank

data class ConnectJiraInstanceRequest(
    @NotBlank
    val name: String,
    @NotBlank
    val url: String,
    @NotBlank
    val tokenName: String,
    @NotBlank
    val projectId: String,
)
