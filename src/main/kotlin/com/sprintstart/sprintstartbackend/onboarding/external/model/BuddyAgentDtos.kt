package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DTOs for the AI service's `POST /api/v1/onboarding/buddy/agent` endpoint — the tool-using buddy.
 *
 * The AI service is a stateless reasoner: the backend carries the running [messages] list between
 * calls and owns tool execution for tools only it can run ([backendTools]). ``search_docs`` is run
 * AI-side; a backend tool comes back in [BuddyAgentResponse.pendingToolCalls] for the backend to
 * execute and feed back as a `tool` message.
 */
@Serializable
data class BuddyToolCallDto(
    val id: String,
    val name: String,
    // Opaque to the backend for a no-argument tool; round-tripped verbatim when carried back.
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BuddyAgentMessageDto(
    // One of system | user | assistant | tool.
    val role: String,
    val content: String = "",
    @SerialName("tool_calls") val toolCalls: List<BuddyToolCallDto> = emptyList(),
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class BuddyToolSpecDto(
    val name: String,
    val description: String,
    // JSON-schema of the tool's arguments.
    val parameters: JsonObject,
)

@Serializable
data class BuddyAgentRequest(
    val messages: List<BuddyAgentMessageDto>,
    @SerialName("backend_tools") val backendTools: List<BuddyToolSpecDto> = emptyList(),
    /**
     * The session's running summary of everything older than [messages] — the conversation the
     * window no longer carries. Sent on the first hop of a turn; after that the AI has folded it
     * into the running [messages] list it returns, so it round-trips on its own.
     */
    @SerialName("prior_summary") val priorSummary: String? = null,
    /**
     * When set, the AI must fold the first this-many messages of [messages] into the summary and
     * return it as [BuddyAgentResponse.updatedSummary]. How the backend bounds an unbounded
     * transcript: the window stays small, and the summary accretes what slides out of it.
     */
    @SerialName("summarize_upto") val summarizeUpto: Int? = null,
)

@Serializable
data class BuddyCitationDto(
    @SerialName("artifact_id") val artifactId: String? = null,
    @SerialName("start_line") val startLine: Int? = null,
    @SerialName("start_page") val startPage: Int? = null,
)

@Serializable
data class BuddyAgentResponse(
    // True when [text] is the answer; false when [pendingToolCalls] must be run first.
    val final: Boolean,
    val text: String = "",
    // The full running conversation to carry back verbatim on a resume.
    val messages: List<BuddyAgentMessageDto> = emptyList(),
    @SerialName("pending_tool_calls") val pendingToolCalls: List<BuddyToolCallDto> = emptyList(),
    val citations: List<BuddyCitationDto> = emptyList(),
    /**
     * The accreted summary when the request asked for compaction (`summarize_upto`): covers the
     * prior summary plus the folded messages. The backend persists it and advances its cursor.
     */
    @SerialName("updated_summary") val updatedSummary: String? = null,
)
