package com.sprintstart.sprintstartbackend.upload.model.dto.request

import jakarta.validation.constraints.NotBlank
import java.util.UUID

data class DeleteArtifactsRequest(
    @NotBlank
    val artifactIds: Set<UUID>,
    @NotBlank
    val removerId: UUID,
    @NotBlank
    val projectId: UUID,
)
