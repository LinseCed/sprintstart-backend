package com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions

data class JiraResourceNotFoundException(
    val msg: String,
) : RuntimeException(msg)
