package com.sprintstart.sprintstartbackend.chat.models.responses

import com.sprintstart.sprintstartbackend.chat.models.ContextRole
import com.sprintstart.sprintstartbackend.chat.models.ChatMessage

internal data class GetChatMessagesResponse(
    val messages: List<ChatMessageResponse>
)

internal data class ChatMessageResponse(
    val role: ContextRole,
    val content: String,
) 

internal fun ChatMessage.toChatMessageResponse(): ChatMessageResponse {
    return ChatMessageResponse(
        role = this.role,
        content = this.content
    )
}
