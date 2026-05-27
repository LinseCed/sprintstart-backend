package com.sprintstart.sprintstartbackend.chat.models.requests

import com.sprintstart.sprintstartbackend.chat.models.ChatMessage
import com.sprintstart.sprintstartbackend.chat.models.ContextRole
import jakarta.validation.constraints.NotBlank
import kotlinx.serialization.Serializable

@Serializable
internal data class AiPromptRequest(
    @NotBlank val prompt: String,
    val context: List<ContextEntry>,
)

@Serializable
internal data class ContextEntry(
    @NotBlank val role: ContextRole,
    @NotBlank val content: String
)

internal fun ChatMessage.toAiContextEntry(): ContextEntry {
    return ContextEntry(
        role = this.role,
        content = this.content 
    )
}
