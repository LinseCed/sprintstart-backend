package com.sprintstart.sprintstartbackend.chat.models.requests

import jakarta.validation.constraints.NotBlank
import java.util.*

data class PromptRequest(
    @NotBlank val chatId: UUID,
    @NotBlank val msg: String,
)

