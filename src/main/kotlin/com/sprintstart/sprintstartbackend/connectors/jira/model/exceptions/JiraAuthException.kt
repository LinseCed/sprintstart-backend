package com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions

import org.springframework.http.HttpStatus

internal data class JiraAuthException(
    val code: HttpStatus,
    val msg: String,
) : RuntimeException("Request to jira instance returned $code: $msg")
