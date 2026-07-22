package com.sprintstart.sprintstartbackend.onboarding.model.response.metrics

import java.time.Instant
import java.util.UUID

/**
 * One hire's onboarding, as a sequence of moments and the gaps between them.
 *
 * Every timestamp is nullable and every gap is nullable, because "has not happened yet" is the
 * normal state of a hire mid-onboarding and is a different thing from zero. A dashboard that
 * renders an unreached milestone as `0 days` reports success where there is none.
 */
data class HireTimelineResponse(
    val userId: UUID,
    val displayName: String,
    /** Null when this person has declared no GitHub login, so none of their work can be attributed. */
    val githubLogin: String?,
    /** Null for assignments made before joining was recorded — "clock unknown", not "joined now". */
    val joinedAt: Instant?,
    /**
     * When the hire was auto-assigned their Task 0 — the trivial first task that proves the loop.
     * Distinct from [firstTaskClaimedAt], which is a goal the hire chose; this one is handed to them
     * on their first read. Null when none has been assigned.
     */
    val taskZeroAssignedAt: Instant?,
    val firstTaskClaimedAt: Instant?,
    val firstPullRequestOpenedAt: Instant?,
    val firstResponseAt: Instant?,
    val firstPullRequestMergedAt: Instant?,
    /** Joined → first merged pull request. The north star, per hire. */
    val hoursToFirstMergedPullRequest: Long?,
    /** Opened → first response on their first pull request. */
    val hoursToFirstResponse: Long?,
    val mergedPullRequestCount: Int,
    val openPullRequestCount: Int,
    /**
     * Their longest pull request currently waiting on anyone, in hours.
     *
     * Measured against now, not against a close that never came: a pull request nobody has answered
     * for three weeks should read as three weeks, and it only stops growing when somebody replies.
     */
    val longestOpenWaitHours: Long?,
    val stalled: Boolean,
    /** What the stall is attributed to, in plain words; null when not stalled. */
    val stalledReason: String?,
    /**
     * When this hire reached autonomy — a task completed with no buddy intervention and no review
     * rework. Null while onboarding is still going.
     *
     * The end of onboarding is a dated event rather than a threshold crossed, so a PM sees *when*
     * somebody became independent rather than a percentage that happened to reach 100.
     */
    val autonomyReachedAt: Instant?,
    /**
     * How many of this hire's pull requests were sent back for changes.
     *
     * The counterpart to the merge count: shipping five changes that each needed three rounds is a
     * different story from shipping five clean ones, and only one of those two numbers was visible
     * before.
     */
    val reworkedPullRequestCount: Int,
)

/**
 * A project's onboarding health.
 *
 * Medians rather than means throughout: one hire who took four months to their first merge should
 * not be able to make the cohort look slow, and one who merged on day one should not hide the rest.
 */
data class ProjectOnboardingMetricsResponse(
    val projectId: UUID,
    val memberCount: Int,
    /** Members with no declared GitHub login — their timelines are necessarily incomplete. */
    val unattributableMemberCount: Int,
    val hiresWithMergedPullRequest: Int,
    val medianHoursToFirstMergedPullRequest: Long?,
    val medianHoursToFirstResponse: Long?,
    /** The slow tail of review latency, where the barrier actually bites. */
    val p90HoursToFirstResponse: Long?,
    val stalledCount: Int,
    val waitingOnResponseCount: Int,
    val hires: List<HireTimelineResponse>,
)
