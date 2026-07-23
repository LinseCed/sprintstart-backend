package com.sprintstart.sprintstartbackend.connectors.jira.model.api.response

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.serializer.CustomAdfDeserializer
import kotlinx.serialization.Serializable
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonSerialize
import java.time.OffsetDateTime

@Serializable
data class JiraIssueResponse(
    val key: String,
    val fields: JiraIssueFields,
)

@Serializable
data class JiraIssueFields(
    val summary: String,
    val description: JiraIssueDescription,
    val comment: JiraIssueCommentField,
)

@Serializable
data class JiraIssueDescription(
    val type: String,
    val version: Int,
    @JsonDeserialize(using = CustomAdfDeserializer::class)
    val content: String,
)

@Serializable
data class JiraIssueCommentField(
    val comments: List<JiraIssueComment>,
)

@Serializable
data class JiraIssueComment(
    val author: JiraIssueCommentAuthor,
    val body: JiraIssueCommentBody,
)

@Serializable
data class JiraIssueCommentAuthor(
    val displayName: String,
    val active: Boolean,
    val created: OffsetDateTime,
    val updated: OffsetDateTime,
)

@Serializable
data class JiraIssueCommentBody(
    @JsonDeserialize(using = CustomAdfDeserializer::class)
    val content: String,
)
