package com.sprintstart.sprintstartbackend.upload.external.events.ingestion

import java.util.UUID

data class UploadArtifactOutcome(
    val id: UUID?,
    val filename: String,
    val status: UploadArtifactStatus,
    val error: String? = null,
)