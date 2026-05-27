package com.sprintstart.sprintstartbackend.chat.models.requests

import jakarta.validation.constraints.NotBlank
import java.time.OffsetDateTime
import java.util.UUID

internal data class CreateChatRequest(
    @NotBlank val title: String,
    @NotBlank val userId: UUID,
)
