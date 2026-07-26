package com.sprintstart.sprintstartbackend.ingestion.model.dto

import java.time.Instant

data class JiraProject(
    val key: String,
    val name: String,
    val projectTypeKey: String,
) : ArtifactMetadata

data class JiraIssueType(
    val name: String,
    val description: String,
) : ArtifactMetadata

data class JiraIssueComment(
    val author: JiraAuthor,
    val content: String,
) : ArtifactMetadata

data class JiraIssueHistory(
    val historyItems: List<JiraIssueHistoryItem>,
) : ArtifactMetadata

data class JiraIssueHistoryItem(
    val author: JiraAuthor,
    val createdAt: Instant,
    val items: List<JiraIssueHistorySubitem>,
)

data class JiraIssueHistorySubitem(
    val field: String,
    val fieldtype: String,
    val from: String,
    val to: String,
)

data class JiraAuthor(
    val displayName: String,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
