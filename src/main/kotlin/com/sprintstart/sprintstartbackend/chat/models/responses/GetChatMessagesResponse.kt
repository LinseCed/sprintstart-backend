package com.sprintstart.sprintstartbackend.chat.models.responses

import com.sprintstart.sprintstartbackend.chat.models.ChatMessage
import com.sprintstart.sprintstartbackend.chat.models.ChatRole
import com.sprintstart.sprintstartbackend.chat.models.Citation
import java.util.UUID

/**
 * The response to send out to the frontend including the messages of a specific chat.
 *
 * @property messages The messages of the chat.
 */
internal data class GetChatMessagesResponse(
    val messages: List<ChatMessageResponse>,
)

internal data class CitationResponse(
    val id: UUID,
    val chunkId: String,
    val filename: String,
)

internal data class ChatMessageResponse(
    val role: ChatRole,
    val content: String,
    val citations: List<CitationResponse> = emptyList(),
)

internal fun Citation.toResponse() =
    CitationResponse(
        id = id,
        chunkId = chunkId,
        filename = filename,
    )

internal fun ChatMessage.toChatMessageResponse(): ChatMessageResponse {
    return ChatMessageResponse(
        role = this.role,
        content = this.content,
        citations = citations.map { it.toResponse() },
    )
}
