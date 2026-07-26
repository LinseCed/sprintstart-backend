package com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions

internal data class JiraResourceNotFoundException(
    val msg: String,
) : RuntimeException(msg)
