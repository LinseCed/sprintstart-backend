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
    /**
     * What one unit of this hire's accepted work is called, for the mentor's persona.
     *
     * Three structured fields rather than persona prose: the AI renders them into a fixed sentence
     * skeleton, so however many tracks exist the mentor stays one voice. Defaults to the
     * engineering wording on both sides, so an older AI service — or a hire on no track — reads
     * exactly as it did before tracks existed.
     */
    @SerialName("vocabulary") val vocabulary: BuddyVocabularyDto = BuddyVocabularyDto(),
)

/**
 * The nouns and verb one track's accepted work is described with.
 *
 * [contributionNoun] is bare ("change", "ceremony") because it is always rendered next to
 * [contributionVerbPast]; baking the verb into the noun produces "merged merged change" the moment
 * a sentence needs both.
 */
@Serializable
data class BuddyVocabularyDto(
    @SerialName("contribution_noun") val contributionNoun: String = "change",
    @SerialName("contribution_noun_plural") val contributionNounPlural: String = "changes",
    @SerialName("contribution_verb_past") val contributionVerbPast: String = "merged",
)

@Serializable
data class BuddyCitationDto(
    @SerialName("artifact_id") val artifactId: String? = null,
    @SerialName("start_line") val startLine: Int? = null,
    @SerialName("start_page") val startPage: Int? = null,
)

/**
 * DTOs for the AI service's `POST /api/v1/onboarding/buddy/open` endpoint.
 *
 * Opening a visit folds the previous visit ([recent]) into the mentor's durable [memory] and
 * returns a warm, proactive [BuddyOpenResponse.greeting] grounded in [state] (a plain-text snapshot
 * of the hire's pull requests, tasks and competencies). Stateless like every AI endpoint: the
 * backend supplies the prior memory and persists the returned one.
 */
@Serializable
data class BuddyOpenRequest(
    val memory: String? = null,
    val recent: List<BuddyAgentMessageDto> = emptyList(),
    val state: String = "",
)

@Serializable
data class BuddyOpenActionDto(
    val label: String,
    val question: String,
)

@Serializable
data class BuddyOpenResponse(
    val memory: String,
    val greeting: String,
    val action: BuddyOpenActionDto? = null,
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
