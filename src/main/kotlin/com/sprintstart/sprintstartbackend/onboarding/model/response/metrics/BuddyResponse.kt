package com.sprintstart.sprintstartbackend.onboarding.model.response.metrics

import java.time.Instant
import java.util.UUID

/** The person a hire can ask, as the hire sees it. */
data class MyBuddyResponse(
    val buddyId: UUID,
    val buddyName: String,
    val projectId: UUID,
    val assignedAt: Instant,
    val cadenceTargetDays: Int,
    val lastContactAt: Instant?,
    /** Days since the last contact, or since assignment when there has never been one. */
    val daysSinceContact: Long,
    /** True once that gap has passed the target — a prompt to the pair, not a reprimand. */
    val overdue: Boolean,
)

/** One hire and their buddy, as whoever is responsible for the project sees it. */
data class BuddyAssignmentResponse(
    val hireId: UUID,
    val hireName: String,
    val buddyId: UUID,
    val buddyName: String,
    val assignedAt: Instant,
    val cadenceTargetDays: Int,
    val lastContactAt: Instant?,
    val contactCount: Int,
)

/**
 * Why one particular person needs a human today, and who should act.
 *
 * [ownedByBuddy] separates "somebody owes this hire a reply" from "this hire needs help finding
 * their way", because those go to different people. A dashboard that reports both as *the hire is
 * behind* points the attention at the one person who cannot fix it.
 */
data class AttentionItemResponse(
    val hireId: UUID,
    val hireName: String,
    val reason: String,
    val severity: AttentionSeverity,
    val ownedByBuddy: Boolean,
    val buddyId: UUID?,
    val buddyName: String?,
    /** How long this has been true, in days — the thing that makes it urgent or not. */
    val days: Long,
)

enum class AttentionSeverity {
    /** Somebody is blocked and waiting on another person right now. */
    BLOCKED,

    /** Drifting: no contact, no attributable identity, no buddy at all. */
    DRIFTING,
}

/** The project's human loop in one read: who is covered, who is waiting, who is drifting. */
data class ProjectAttentionResponse(
    val projectId: UUID,
    val memberCount: Int,
    val withBuddyCount: Int,
    /** Contacts logged across the project in the last 30 days — is the loop actually running? */
    val recentContactCount: Int,
    val items: List<AttentionItemResponse>,
)
