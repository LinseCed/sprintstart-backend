package com.sprintstart.sprintstartbackend.onboarding.model.response.buddy

/**
 * What the buddy opens a visit with: a proactive, mentor-written greeting, and optionally one
 * suggested next step the hire can act on with a single click.
 *
 * The hire's past transcript is deliberately not returned — a visit starts fresh with this greeting,
 * and the buddy's continuity across visits lives in its durable memory, not in a replayed log.
 */
data class BuddyOpeningResponse(
    val greeting: String,
    val action: BuddyOpeningActionResponse? = null,
)

data class BuddyOpeningActionResponse(
    val label: String,
    val question: String,
)
