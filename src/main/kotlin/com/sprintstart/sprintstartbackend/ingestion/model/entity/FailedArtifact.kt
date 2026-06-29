package com.sprintstart.sprintstartbackend.ingestion.model.entity

import jakarta.persistence.Embeddable

@Embeddable
data class FailedArtifact(
    val sourceId: String?,
    val artifactType: ArtifactType,
    val sourceUrl: String?,
    val reason: String,
)
