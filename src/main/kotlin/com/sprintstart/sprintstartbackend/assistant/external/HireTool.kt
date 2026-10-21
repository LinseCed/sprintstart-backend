package com.sprintstart.sprintstartbackend.assistant.external

import com.sprintstart.sprintstartbackend.shared.ids.UserId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Who a hire tool runs for, and where in the app they were when they asked. */
data class HireToolContext(
    val hire: UserId,
    /** The app path the hire was on when they spoke; null when not sent. */
    val currentPage: String? = null,
)

/**
 * A read-only tool a feature lends the hire's Buddy, from `<feature>/assistant/`.
 *
 * The Buddy mounts every bean of this type that [isMountedFor] the caller, in `@Order` order (see
 * [HireToolOrder]), because the spec order is the order the reasoner sees. A tool only ever reads the
 * caller: [HireToolContext.hire] is the authenticated hire, never an id the model supplied.
 */
interface HireTool {
    val spec: BuddyToolSpecDto

    /**
     * Whether to hand this tool to [hire] at all. "Absent, never empty": a tool whose answer would be
     * "nothing" for this hire is not mounted, because a mentor holding it finds something to say anyway.
     */
    fun isMountedFor(hire: UserId): Boolean = true

    fun execute(call: BuddyToolCallDto, context: HireToolContext): String

    /** This tool's part of the opening greeting's snapshot of the hire, or null to add nothing. */
    fun snapshotFor(hire: UserId): String? = null

    /**
     * The chip offered for this tool whenever it is mounted, or null for none. Adding a tool never
     * silently adds a chip: the wording for a hire is a separate decision from what the mentor can read.
     */
    val suggestion: HireToolSuggestion? get() = null
}

/**
 * A suggestion chip. [label] is what the chip says; [question] is what lands in the composer. Wording may
 * only assume what the tool's mount condition guarantees.
 */
data class HireToolSuggestion(
    val label: String,
    val question: String,
)

/**
 * Where each feature's hire tools sit in the list the reasoner sees, lowest first. Gaps leave room to
 * place a new tool between two others without renumbering.
 */
object HireToolOrder {
    /** What has to be true before the hire can work: after the path, before every other state tool. */
    const val ARRIVAL = 200
}

/** The parameter schema of a tool that takes no arguments. */
fun noArgumentsSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }
