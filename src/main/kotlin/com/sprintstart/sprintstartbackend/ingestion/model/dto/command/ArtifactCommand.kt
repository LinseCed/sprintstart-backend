package com.sprintstart.sprintstartbackend.ingestion.model.dto.command

import com.sprintstart.sprintstartbackend.ingestion.model.dto.ArtifactMetadata
import com.sprintstart.sprintstartbackend.ingestion.model.dto.GithubArtifactMetadata
import com.sprintstart.sprintstartbackend.ingestion.model.dto.UploadArtifactMetadata
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import java.time.Instant
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
    // Only populated for ArtifactType.ISSUE today; null/empty for other GitHub artifact types.
    val state: String? = null,
    val labels: List<String> = emptyList(),
    /**
     * GitHub login of whoever authored this artifact at the source, lower-cased.
     *
     * Only ISSUE and PULL_REQUEST carry one: those come from the GraphQL API, which returns a real
     * `author.login`. Commits are parsed out of `git log --pretty=%an`, which is a *git author
     * name* ("Ada Lovelace"), not a GitHub account -- treating it as a login would attribute work
     * to the wrong person. Files have no single author at all.
     */
    val authorLogin: String? = null,
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
