package com.sprintstart.sprintstartbackend.chat.models.requests

import com.sprintstart.sprintstartbackend.chat.models.ChatMessage
import com.sprintstart.sprintstartbackend.chat.models.ChatRole
import jakarta.validation.constraints.NotBlank

/**
 * Used for prompting the ai on a new prompt, providing the chat context
 *
 * @property prompt The new prompt the ai should answer
 * @property context All relevant chat context, e.g. previous messages in this chat
 */
internal data class AiPromptRequest(
    @NotBlank val prompt: String,
    val context: List<ContextEntry>,
)

internal data class ContextEntry(
    @NotBlank val role: ChatRole,
    @NotBlank val content: String,
)

internal fun ChatMessage.toAiContextEntry(): ContextEntry {
    return ContextEntry(
        role = this.role,
        content = this.content,
    )
}
