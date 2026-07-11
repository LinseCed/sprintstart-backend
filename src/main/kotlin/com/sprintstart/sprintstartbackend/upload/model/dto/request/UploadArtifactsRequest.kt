package com.sprintstart.sprintstartbackend.upload.model.dto.request

import jakarta.validation.constraints.NotBlank
import java.util.UUID

data class UploadArtifactsRequest(
    @NotBlank
    val uploaderId: UUID,
    @NotBlank
    val projectId: UUID,
)
