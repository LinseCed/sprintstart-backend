package com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions

data class JiraAuthException(
    val msg: String,
) : RuntimeException(msg)
