package com.sprintstart.sprintstartbackend.github.external.events

import java.util.UUID

data class GithubIssueFetchedEvent(
    val transactionId: UUID,
    val number: Int,
    val title: String,
    val state: String?,
    val createdAt: String,
    val closedAt: String?,
    val url: String,
    val author: String?,
    val labels: List<String>,
    val assignees: List<String>,
    val comments: List<GithubIssueComment>,
)

data class GithubIssueComment(
    val body: String,
    val author: String?,
    val createdAt: String,
)
