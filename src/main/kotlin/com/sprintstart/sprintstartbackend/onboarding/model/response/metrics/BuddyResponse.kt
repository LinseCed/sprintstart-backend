package com.sprintstart.sprintstartbackend.onboarding.model.response.metrics

import java.time.Instant
import java.util.UUID

/** The person a hire can ask, as the hire sees it. */
data class MyBuddyResponse(
    val buddyId: UUID,
    val buddyName: String,
    /**
     * The buddy's GitHub handle, when they have declared one — the one concrete way to reach them
     * this product already knows. Null when unknown, in which case the hire is pointed at their
     * usual channel rather than a dead link.
     */
    val buddyGithubLogin: String?,
    val projectId: UUID,
    val assignedAt: Instant,
    val cadenceTargetDays: Int,
    val lastContactAt: Instant?,
    /** Days since the last contact, or since assignment when there has never been one. */
    val daysSinceContact: Long,
    /** True once that gap has passed the target — a prompt to the pair, not a reprimand. */
    val overdue: Boolean,
)

/**
 * The other end of the relationship: the hires who are counting on *me*, as their buddy sees them.
 *
 * The whole point of the slice is that mentoring is the highest-evidence lever, and it only works
 * if the mentor knows a hire is waiting. Until now the overdue-contact and waiting-on-a-review
 * signals reached the hire (who cannot act on their own move) and the project's lead (who has to
 * relay it) — but never the buddy, the one person who closes the loop in real life.
 */
data class MyMenteesResponse(
    val mentees: List<MenteeResponse>,
)

/** One hire this buddy is responsible for, with whatever is currently their move to make. */
data class MenteeResponse(
    val hireId: UUID,
    val hireName: String,
    /** The hire's GitHub handle when known — the concrete way to reach them; null → usual channel. */
    val hireGithubLogin: String?,
    val projectId: UUID,
    val cadenceTargetDays: Int,
    val assignedAt: Instant,
    val lastContactAt: Instant?,
    /** Days since the last contact, or since assignment when there has never been one. */
    val daysSinceContact: Long,
    /** True once that gap has passed the cadence target — the pair is due a conversation. */
    val overdue: Boolean,
    /**
     * The items that are this buddy's move, worst first: a review kept waiting, a cadence gone
     * quiet, a stall. Empty when the pair is on track — the calm state is a real answer, not a gap.
     */
    val alerts: List<MenteeAlertResponse>,
)

/** One thing a buddy could do for a hire right now. Always the buddy's move — the list is filtered. */
data class MenteeAlertResponse(
    val reason: String,
    val severity: AttentionSeverity,
    val days: Long,
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
