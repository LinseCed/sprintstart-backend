package com.sprintstart.sprintstartbackend.onboarding.model.request.buddy

/**
 * A hire confirming a buddy-proposed action.
 *
 * [action] is the proposed action's tool name (see `BuddyActionType`). The project is **not**
 * carried here — it is re-resolved server-side from the caller, so a client can never confirm an
 * action against a project the buddy did not scope it to. [question] rides along only for the
 * flag-to-PM action (the text the buddy composed and showed the hire); [note] is an optional line
 * for logging buddy contact. Both are ignored by the actions that don't use them.
 */
data class BuddyActionRequest(
    val action: String,
    val question: String? = null,
    val note: String? = null,
)
