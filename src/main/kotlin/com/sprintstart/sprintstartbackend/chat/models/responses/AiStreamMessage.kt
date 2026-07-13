package com.sprintstart.sprintstartbackend.chat.models.responses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Specifies the format of streamed messages incoming from the AI repo.
 *
 * The AI service multiplexes several event shapes over a single SSE stream, all
 * distinguished by [type]. Only the fields relevant to a given [type] are populated;
 * the rest stay `null` and are omitted on (de)serialization. The backend acts as a
 * transparent passthrough, so the wire field names mirror the AI service contract
 * exactly (snake_case where applicable) and are forwarded unchanged to the client.
 *
 * Event shapes:
 * - `tool_use`: [name] + [kind] — an agent/tool the orchestrator invoked.
 * - `token`: [content] — a fragment of the generated answer.
 * - `citation`: [artifactId] + [startLine] + [startPage] — a source used in the answer.
 *   The AI service only sends what it owns (the chunk's artifact reference and its
 *   position within that artifact); the backend resolves filename/source URL itself
 *   via [com.sprintstart.sprintstartbackend.chat.service.ArtifactLookupService].
 * - `done`: terminal marker, no extra fields.
 * - `error`: [message] — the stream failed.
 *
 * @property type The type of stream message this is (e.g. 'token', 'tool_use', 'citation', 'done', 'error').
 * @property content For `token` events: a single token or short fragment of the answer.
 * @property name For `tool_use` events: the name of the invoked capability (e.g. 'retrieve').
 * @property kind For `tool_use` events: whether the capability is a leaf 'tool' or a sub-'agent'.
 * @property artifactId For `citation` events: the id of the artifact the cited chunk belongs to.
 * @property startLine For `citation` events: 1-based source line the chunk starts on, or null.
 * @property startPage For `citation` events: 1-based PDF page the chunk was extracted from, or null.
 * @property message For `error` events: the error description.
 */
@Serializable
data class AiStreamMessage(
    val type: String,
    val content: String? = null,
    val name: String? = null,
    val kind: String? = null,
    @SerialName("artifact_id") val artifactId: String? = null,
    @SerialName("start_line") val startLine: Int? = null,
    @SerialName("start_page") val startPage: Int? = null,
    val message: String? = null,
)
