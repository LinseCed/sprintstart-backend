package com.sprintstart.sprintstartbackend.ingestion.model.dto

import java.util.UUID

sealed interface ArtifactMetadata

data class GithubArtifactMetadata(
    val repositoryId: UUID,
    val repositoryFullName: String,
) : ArtifactMetadata

data class UploadArtifactMetadata(
    var storagePath: String? = null,
    var actorId: UUID,
) : ArtifactMetadata
