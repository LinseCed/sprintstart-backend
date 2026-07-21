package com.sprintstart.sprintstartbackend.connectors.jira.model.api.response

import kotlinx.serialization.Serializable

@Serializable
data class GetProjectsOfInstanceResponse(
    val projects: List<Project>,
)

@Serializable
data class Project(
    val key: String,
)
