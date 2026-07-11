package com.sprintstart.sprintstartbackend.upload.external.events

import com.sprintstart.sprintstartbackend.upload.model.dto.UploadArtifactOutcome
import java.util.UUID

data class UploadBatchFinishedEvent(
    val transactionId: UUID,
    val artifactsId: Set<UUID>,
    val linkedImages: Set<UUID>,
    val uploadArtifactOutcomes : Set<UploadArtifactOutcome>,
)
