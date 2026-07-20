package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.ingestion.external.AuthoredPullRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.HireTimelineResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.ProjectOnboardingMetricsResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGoalRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * How long onboarding is actually taking, derived from what the system already records.
 *
 * ### Why this is derived rather than emitted
 *
 * There is no onboarding event table. Every fact here already exists somewhere durable — a project
 * assignment, a claimed goal, an ingested pull request — and copying those into a second log would
 * create two versions of the same truth that drift, plus a backfill problem for everything that
 * happened before the log existed. Deriving means the metrics cover history from the day this
 * shipped, without migrating anything.
 *
 * Events that are *not* derivable from existing rows — a buddy conversation, an environment that
 * came up on somebody's laptop — will need somewhere to be written. That is deliberately deferred
 * to the slices that introduce them, so the storage decision is made when there is a real event to
 * store rather than in advance.
 *
 * ### What it will not do
 *
 * Nothing here reports a percentage of anything completed. The measure of onboarding is
 * time-to-first-merged-pull-request and time-to-autonomy; a completion percentage over generated
 * content is the metric this initiative moved away from, and republishing it under a new name would
 * put it straight back.
 */
@Service
class OnboardingMetricsService(
    private val projectMembershipApi: ProjectMembershipApi,
    private val artifactIngestionApi: ArtifactIngestionApi,
    private val userGoalRepository: UserGoalRepository,
    private val environmentReadinessService: EnvironmentReadinessService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Every member of a project with their timeline, plus the aggregates a PM acts on.
     *
     * @param projectId The project to report on.
     */
    @Transactional(readOnly = true)
    fun getProjectMetrics(projectId: UUID): ProjectOnboardingMetricsResponse {
        val members = projectMembershipApi.getProjectMembers(projectId)
        val hires = members.map { timelineFor(it, projectId) }

        val timesToMerge = hires.mapNotNull { it.hoursToFirstMergedPullRequest }
        val timesToResponse = hires.mapNotNull { it.hoursToFirstResponse }

        return ProjectOnboardingMetricsResponse(
            projectId = projectId,
            memberCount = members.size,
            unattributableMemberCount = members.count { it.githubLogin.isNullOrBlank() },
            hiresWithMergedPullRequest = hires.count { it.mergedPullRequestCount > 0 },
            medianHoursToFirstMergedPullRequest = median(timesToMerge),
            medianHoursToFirstResponse = median(timesToResponse),
            p90HoursToFirstResponse = percentile(timesToResponse, P90),
            stalledCount = hires.count { it.stalled },
            waitingOnResponseCount = hires.count { it.longestOpenWaitHours != null },
            hires = hires,
        )
    }

    /**
     * One hire's timeline in one project.
     *
     * @throws NoSuchElementException never — an unknown user simply has no membership and is
     * reported as absent by the caller.
     */
    @Transactional(readOnly = true)
    fun getHireTimeline(userId: UUID, projectId: UUID): HireTimelineResponse? {
        val member = projectMembershipApi.getProjectMembers(projectId).firstOrNull { it.userId == userId }
            ?: return null
        return timelineFor(member, projectId)
    }

    private fun timelineFor(member: ProjectMember, projectId: UUID): HireTimelineResponse {
        val now = clock.instant()
        val login = member.githubLogin

        // No declared login means no attribution is possible. Reporting that as "opened no pull
        // requests" would be a lie about the person rather than about the data.
        val pullRequests = if (login.isNullOrBlank()) {
            emptyList()
        } else {
            artifactIngestionApi.getAuthoredPullRequests(projectId, login)
        }

        val opened = pullRequests.mapNotNull { it.openedAt }.minOrNull()
        val merged = pullRequests.mapNotNull { it.mergedAt }.minOrNull()
        val firstPullRequest = pullRequests
            .filter { it.openedAt != null }
            .minByOrNull { it.openedAt as Instant }

        val goalClaimedAt = userGoalRepository.findByUserIdAndProjectId(member.userId, projectId)?.claimedAt

        val longestOpenWait = pullRequests
            .filter { it.firstResponseAt == null && it.mergedAt == null && it.openedAt != null }
            .mapNotNull { it.openedAt }
            .minOrNull()
            ?.let { hoursBetween(it, now) }

        val stalledReason = stalledReason(member, pullRequests, goalClaimedAt, merged, now)

        return HireTimelineResponse(
            userId = member.userId,
            displayName = member.displayName,
            githubLogin = login,
            joinedAt = member.joinedAt,
            envReadyAt = environmentReadinessService.readyAtFor(member, projectId),
            firstTaskClaimedAt = goalClaimedAt,
            firstPullRequestOpenedAt = opened,
            firstResponseAt = firstPullRequest?.firstResponseAt,
            firstPullRequestMergedAt = merged,
            hoursToFirstMergedPullRequest = hoursBetween(member.joinedAt, merged),
            hoursToFirstResponse = hoursBetween(firstPullRequest?.openedAt, firstPullRequest?.firstResponseAt),
            mergedPullRequestCount = pullRequests.count { it.mergedAt != null },
            openPullRequestCount = pullRequests.count { it.mergedAt == null },
            longestOpenWaitHours = longestOpenWait,
            stalled = stalledReason != null,
            stalledReason = stalledReason,
        )
    }

    /**
     * Why this hire is stuck, in the words a PM would use — or null if they are not.
     *
     * The reasons are ordered by what a PM should do about them, not by severity. A pull request
     * waiting on a review is somebody else's action and is named first; a hire who has not opened
     * anything is a conversation.
     */
    private fun stalledReason(
        member: ProjectMember,
        pullRequests: List<AuthoredPullRequest>,
        goalClaimedAt: Instant?,
        firstMergedAt: Instant?,
        now: Instant,
    ): String? {
        if (member.githubLogin.isNullOrBlank()) {
            return "No GitHub username on record, so none of their work can be attributed"
        }

        val waitingSince = pullRequests
            .filter { it.firstResponseAt == null && it.mergedAt == null }
            .mapNotNull { it.openedAt }
            .minOrNull()
        if (waitingSince != null && hoursBetween(waitingSince, now)!! >= RESPONSE_SLA_HOURS) {
            val days = hoursBetween(waitingSince, now)!! / HOURS_PER_DAY
            return "A pull request has been waiting $days days for a first response"
        }

        // Merged something already: onboarding is moving, whatever else is open.
        if (firstMergedAt != null) {
            return null
        }

        val since = listOfNotNull(goalClaimedAt, member.joinedAt).maxOrNull() ?: return null
        val quietHours = hoursBetween(since, now) ?: return null
        if (pullRequests.isEmpty() && quietHours >= NO_ACTIVITY_STALL_HOURS) {
            val days = quietHours / HOURS_PER_DAY
            return "No pull request opened in $days days since joining"
        }

        return null
    }

    private fun hoursBetween(from: Instant?, to: Instant?): Long? {
        if (from == null || to == null || to.isBefore(from)) return null
        return Duration.between(from, to).toHours()
    }

    private fun median(values: List<Long>): Long? = percentile(values, MEDIAN)

    /**
     * Nearest-rank percentile. Deliberately not interpolated: these are small cohorts, and an
     * interpolated value invents a hire whose timeline nobody had.
     */
    private fun percentile(values: List<Long>, fraction: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = Math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private companion object {
        const val MEDIAN = 0.5
        const val P90 = 0.9
        const val HOURS_PER_DAY = 24

        /** How long a pull request may wait for any response before it is somebody's problem. */
        const val RESPONSE_SLA_HOURS = 48

        /** How long a hire may be quiet after joining or claiming a task before it is flagged. */
        const val NO_ACTIVITY_STALL_HOURS = 14 * HOURS_PER_DAY
    }
}
