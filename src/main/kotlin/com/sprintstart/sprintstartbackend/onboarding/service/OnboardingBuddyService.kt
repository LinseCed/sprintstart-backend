package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyContact
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingBuddy
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.AttentionItemResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.AttentionSeverity
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.BuddyAssignmentResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.HireTimelineResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.MyBuddyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.ProjectAttentionResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyContactRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingBuddyRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The human half of onboarding: who a hire can ask, whether anyone has actually spoken to them,
 * and who is waiting on somebody else today.
 *
 * Not to be confused with [BuddyService], which is the AI assistant. The naming is unfortunate and
 * the distinction is the whole point of this slice: mentoring carries the largest measured effects
 * in the onboarding literature — mentored developers were substantially more active than
 * unmentored ones entering the same projects, and perceived productivity in the first 90 days rose
 * steeply with how *often* a hire met their buddy rather than with whether they had one. Until
 * now the product's answer to "who helps me" was a chatbot.
 *
 * ### Buddies are optional, on purpose
 *
 * A project can run without any. Some teams have nobody to spare, and a system that refuses to
 * work without a mentor helps nobody. Everything else here still functions: the wait for a first
 * response is still measured, stalls are still raised, and a hire with no buddy becomes an
 * attention item of its own rather than a silent gap.
 *
 * ### What it deliberately does not do
 *
 * It does not score buddies, rank them, or read what they said. The note on a contact is for the
 * pair, not for analysis. Turning a relationship into a performance metric is the fastest way to
 * make people stop recording it honestly.
 */
@Service
class OnboardingBuddyService(
    private val onboardingBuddyRepository: OnboardingBuddyRepository,
    private val buddyContactRepository: BuddyContactRepository,
    private val projectMembershipApi: ProjectMembershipApi,
    private val onboardingMetricsService: OnboardingMetricsService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Assigns, or reassigns, a hire's buddy.
     *
     * Both people must already be members of the project — a buddy who cannot see the work is not a
     * buddy — and nobody may be their own, since the point is that there is another person to ask.
     *
     * @throws ResponseStatusException 400 when hire and buddy are the same person or the cadence is
     * not positive; 404 when either is not a member of the project.
     */
    @Transactional
    fun assign(
        projectId: UUID,
        hireId: UUID,
        buddyId: UUID,
        cadenceTargetDays: Int?,
    ): BuddyAssignmentResponse {
        val cadence = cadenceTargetDays ?: OnboardingBuddy.DEFAULT_CADENCE_TARGET_DAYS
        validateAssignment(hireId, buddyId, cadence)

        val members = projectMembershipApi.getProjectMembers(projectId).associateBy { it.userId }
        val hire = members[hireId] ?: throw notAMember(hireId, projectId)
        val buddy = members[buddyId] ?: throw notAMember(buddyId, projectId)

        val existing = onboardingBuddyRepository.findByHireIdAndProjectId(hireId, projectId)
        val assignment = if (existing != null) {
            existing.buddyId = buddyId
            existing.cadenceTargetDays = cadence
            existing
        } else {
            onboardingBuddyRepository.save(
                OnboardingBuddy(
                    hireId = hireId,
                    projectId = projectId,
                    buddyId = buddyId,
                    cadenceTargetDays = cadence,
                    assignedAt = clock.instant(),
                ),
            )
        }

        val contacts = buddyContactRepository.findAllByHireIdAndProjectIdOrderByOccurredAtDesc(hireId, projectId)
        return BuddyAssignmentResponse(
            hireId = hireId,
            hireName = hire.displayName,
            buddyId = buddyId,
            buddyName = buddy.displayName,
            assignedAt = assignment.assignedAt,
            cadenceTargetDays = assignment.cadenceTargetDays,
            lastContactAt = contacts.firstOrNull()?.occurredAt,
            contactCount = contacts.size,
        )
    }

    /**
     * Removes a hire's buddy. Contacts already logged are kept: the conversations happened, and
     * deleting them would rewrite the history the metrics read from.
     */
    @Transactional
    fun unassign(projectId: UUID, hireId: UUID) {
        onboardingBuddyRepository.deleteByHireIdAndProjectId(hireId, projectId)
    }

    /** Every assignment in a project, for whoever is responsible for the team. */
    @Transactional(readOnly = true)
    fun listAssignments(projectId: UUID): List<BuddyAssignmentResponse> {
        val members = projectMembershipApi.getProjectMembers(projectId).associateBy { it.userId }
        val contactsByHire = buddyContactRepository.findAllByProjectId(projectId).groupBy { it.hireId }

        return onboardingBuddyRepository.findAllByProjectId(projectId).map { assignment ->
            val contacts = contactsByHire[assignment.hireId].orEmpty()
            BuddyAssignmentResponse(
                hireId = assignment.hireId,
                hireName = members[assignment.hireId]?.displayName ?: UNKNOWN,
                buddyId = assignment.buddyId,
                buddyName = members[assignment.buddyId]?.displayName ?: UNKNOWN,
                assignedAt = assignment.assignedAt,
                cadenceTargetDays = assignment.cadenceTargetDays,
                lastContactAt = contacts.maxByOrNull { it.occurredAt }?.occurredAt,
                contactCount = contacts.size,
            )
        }
    }

    /** The hire's own view: who to ask, and whether the pair is overdue a conversation. */
    @Transactional(readOnly = true)
    fun getBuddyFor(hireId: UUID, projectId: UUID): MyBuddyResponse? {
        val assignment = onboardingBuddyRepository.findByHireIdAndProjectId(hireId, projectId) ?: return null
        val buddyMember = projectMembershipApi
            .getProjectMembers(projectId)
            .firstOrNull { it.userId == assignment.buddyId }
        val buddyName = buddyMember?.displayName ?: UNKNOWN

        val lastContact = buddyContactRepository
            .findAllByHireIdAndProjectIdOrderByOccurredAtDesc(hireId, projectId)
            .firstOrNull()
            ?.occurredAt

        // Never contacted counts from assignment rather than from epoch: a pairing made this
        // morning is not overdue, and reporting it as such trains people to ignore the signal.
        val days = daysBetween(lastContact ?: assignment.assignedAt, clock.instant())

        return MyBuddyResponse(
            buddyId = assignment.buddyId,
            buddyName = buddyName,
            buddyGithubLogin = buddyMember?.githubLogin,
            projectId = projectId,
            assignedAt = assignment.assignedAt,
            cadenceTargetDays = assignment.cadenceTargetDays,
            lastContactAt = lastContact,
            daysSinceContact = days,
            overdue = days > assignment.cadenceTargetDays,
        )
    }

    /**
     * Records that a conversation happened.
     *
     * Either side may log it, and nothing verifies it. Deliberately: the alternative is making
     * people prove they spoke, which costs more than it is worth and produces a record people
     * resent keeping.
     *
     * @throws ResponseStatusException 404 when the hire is not a member of the project; 400 for a
     * contact dated in the future.
     */
    @Transactional
    fun logContact(projectId: UUID, hireId: UUID, recordedBy: UUID, occurredAt: Instant?, note: String?) {
        val isMember = projectMembershipApi.getProjectMembers(projectId).any { it.userId == hireId }
        if (!isMember) {
            throw notAMember(hireId, projectId)
        }

        val now = clock.instant()
        val at = occurredAt ?: now
        if (at.isAfter(now)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A contact cannot be logged in the future")
        }

        buddyContactRepository.save(
            BuddyContact(
                hireId = hireId,
                projectId = projectId,
                recordedBy = recordedBy,
                occurredAt = at,
                note = note?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
    }

    /**
     * Who needs a human today, worst first.
     *
     * Composed from the derived metrics rather than recomputed, so "waiting on a review" means the
     * same thing here as everywhere else in the product.
     */
    @Transactional(readOnly = true)
    fun getAttention(projectId: UUID): ProjectAttentionResponse {
        val now = clock.instant()
        val members = projectMembershipApi.getProjectMembers(projectId).associateBy { it.userId }
        val assignments = onboardingBuddyRepository.findAllByProjectId(projectId).associateBy { it.hireId }
        val contacts = buddyContactRepository.findAllByProjectId(projectId)
        val lastContactByHire = contacts.groupBy { it.hireId }.mapValues { (_, list) -> list.maxOf { it.occurredAt } }

        val items = onboardingMetricsService.getProjectMetrics(projectId).hires.flatMap { hire ->
            val member = members[hire.userId] ?: return@flatMap emptyList()
            attentionFor(hire, member.displayName, assignments[hire.userId], members, lastContactByHire, now)
        }

        val recentCutoff = now.minus(Duration.ofDays(RECENT_CONTACT_WINDOW_DAYS))
        return ProjectAttentionResponse(
            projectId = projectId,
            memberCount = members.size,
            withBuddyCount = assignments.size,
            recentContactCount = contacts.count { it.occurredAt.isAfter(recentCutoff) },
            // Blocked before drifting, then longest-waiting first: the order a person would work
            // the list in.
            items = items.sortedWith(compareBy({ it.severity }, { -it.days })),
        )
    }

    private fun attentionFor(
        hire: HireTimelineResponse,
        hireName: String,
        assignment: OnboardingBuddy?,
        members: Map<UUID, ProjectMember>,
        lastContactByHire: Map<UUID, Instant>,
        now: Instant,
    ): List<AttentionItemResponse> {
        val items = mutableListOf<AttentionItemResponse>()
        val buddyName = assignment?.let { members[it.buddyId]?.displayName }
        val sinceJoined = daysBetween(members[hire.userId]?.joinedAt ?: now, now)

        fun item(reason: String, severity: AttentionSeverity, ownedByBuddy: Boolean, days: Long) =
            AttentionItemResponse(
                hireId = hire.userId,
                hireName = hireName,
                reason = reason,
                severity = severity,
                ownedByBuddy = ownedByBuddy,
                buddyId = assignment?.buddyId,
                buddyName = buddyName,
                days = days,
            )

        // Waiting on a person comes first: it is the barrier with the most evidence behind it, and
        // unlike everything else on this list it is somebody else's move, not the hire's.
        hire.longestOpenWaitHours?.takeIf { it >= WAITING_ATTENTION_HOURS }?.let { hours ->
            val days = hours / HOURS_PER_DAY
            items += item(
                "A pull request has been waiting $days days for a response",
                AttentionSeverity.BLOCKED,
                ownedByBuddy = true,
                days = days,
            )
        }

        if (assignment == null) {
            items += item(
                "No buddy assigned — nobody is expected to check in on them",
                AttentionSeverity.DRIFTING,
                ownedByBuddy = false,
                days = sinceJoined,
            )
        } else {
            val days = daysBetween(lastContactByHire[hire.userId] ?: assignment.assignedAt, now)
            if (days > assignment.cadenceTargetDays) {
                val everSpoke = lastContactByHire.containsKey(hire.userId)
                items += item(
                    if (everSpoke) {
                        "No contact with their buddy for $days days"
                    } else {
                        "Assigned a buddy $days days ago and they have not spoken yet"
                    },
                    AttentionSeverity.DRIFTING,
                    ownedByBuddy = true,
                    days = days,
                )
            }
        }

        // A stall the metrics found that the rows above do not already cover: no attributable
        // identity, or silence since joining.
        if (hire.stalled && hire.longestOpenWaitHours == null) {
            items += item(
                hire.stalledReason ?: "Stalled",
                AttentionSeverity.DRIFTING,
                ownedByBuddy = assignment != null,
                days = sinceJoined,
            )
        }

        return items
    }

    private fun validateAssignment(hireId: UUID, buddyId: UUID, cadence: Int) {
        if (hireId == buddyId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A hire cannot be their own buddy")
        }
        if (cadence < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cadenceTargetDays must be at least 1")
        }
    }

    private fun daysBetween(from: Instant, to: Instant): Long =
        if (to.isBefore(from)) 0 else Duration.between(from, to).toDays()

    private fun notAMember(userId: UUID, projectId: UUID) =
        ResponseStatusException(HttpStatus.NOT_FOUND, "User $userId is not a member of project $projectId")

    private companion object {
        const val UNKNOWN = "Unknown"
        const val HOURS_PER_DAY = 24

        /** Matches the metrics' response window, so the two numbers cannot contradict each other. */
        const val WAITING_ATTENTION_HOURS = 48
        const val RECENT_CONTACT_WINDOW_DAYS = 30L
    }
}
