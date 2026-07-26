package com.sprintstart.sprintstartbackend.ingestion.model.dto.command

import com.sprintstart.sprintstartbackend.ingestion.external.model.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.dto.ArtifactMetadata
import com.sprintstart.sprintstartbackend.ingestion.model.dto.GithubArtifactMetadata
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraAuthor
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraIssueComment
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraIssueHistory
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraIssueType
import com.sprintstart.sprintstartbackend.ingestion.model.dto.JiraProject
import com.sprintstart.sprintstartbackend.ingestion.model.dto.UploadArtifactMetadata
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

sealed interface ArtifactCommand

data class GithubArtifactCommand(
    val ingestionRunId: UUID,
    val sourceSystem: SourceSystem,
    val sourceId: String,
    val sourceUrl: String?,
    val artifactType: ArtifactType,
    val title: String?,
    val bodyText: String?,
    val mime: String?,
    val language: String?,
    val createdAtSource: Instant?,
    val updatedAtSource: Instant?,
    val hash: String?,
    val metadata: GithubArtifactMetadata,
) : ArtifactCommand

data class JiraArtifactCommand(
    val ingestionRunId: UUID,
    val sourceSystem: SourceSystem,
    val sourceId: String,
    val sourceUrl: String,
    val artifactType: ArtifactType,
    val issueType: JiraIssueType,
    val issueId: String,
    val issueKey: String,
    val summary: String,
    val description: String,
    val createdBy: JiraAuthor,
    val reportedBy: JiraAuthor,
    val assignee: JiraAuthor?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val project: JiraProject,
    val history: JiraIssueHistory,
    val comments: List<JiraIssueComment>,
    val statusName: String,
    val statusDescription: String,
    val statusCategory: String,
) : ArtifactCommand

data class UploadArtifactCommand(
    val ingestionRunId: UUID,
    val projectId: UUID,
    val sourceSystem: SourceSystem,
    val sourceId: String,
    val artifactType: ArtifactType,
    val title: String?,
    val content: String?,
    val mime: String?,
    val language: String?,
    val createdAtSource: Instant?,
    val updatedAtSource: Instant?,
    val hash: String?,
    val metadata: UploadArtifactMetadata,
) : ArtifactCommand

data class ArtifactFailedCommand(
    val transactionId: UUID,
    val sourceId: String?,
    val sourceUrl: String?,
    val artifactType: ArtifactType,
    val reason: String,
    val metadata: ArtifactMetadata,
) : ArtifactCommand
