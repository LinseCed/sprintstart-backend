package com.sprintstart.sprintstartbackend.github.external.events

import java.util.UUID

data class GithubCommitFetchedEvent(
    val transactionId: UUID,
    val oid: String,
    val headline: String,
    val message: String,
    val committedDate: String,
    val authorName: String?,
    val authorEmail: String?,
    val changedFilesIfAvailable: Int?,
    val url: String,
)
