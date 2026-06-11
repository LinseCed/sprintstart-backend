package com.sprintstart.sprintstartbackend.github.external.events

import java.util.UUID

data class GithubPullRequestFetchedEvent(
    val transactionId: UUID,
    val number: Int,
    val body: String?,
    val state: String,
    val createdAt: String,
    val mergedAt: String?,
    val url: String,
    val author: String?,
    val labels: List<String>?,
    val reviews: List<GithubPullRequestReview>?,
    val comments: List<GithubPullRequestComment>?,
    val reviewThreads: List<GithubPullRequestReviewThread>?,
)

data class GithubPullRequestReview(
    val body: String?,
    val state: String,
    val author: String?,
)

data class GithubPullRequestComment(
    val body: String,
    val author: String?,
    val createdAt: String,
)

data class GithubPullRequestReviewThread(
    val comments: List<GithubPullRequestReviewThreadComment>,
)

data class GithubPullRequestReviewThreadComment(
    val body: String,
    val author: String?,
    val path: String,
)
