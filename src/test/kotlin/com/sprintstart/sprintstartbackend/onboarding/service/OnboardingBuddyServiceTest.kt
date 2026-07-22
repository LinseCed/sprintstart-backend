package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyContact
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingBuddy
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.AttentionSeverity
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.HireTimelineResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.ProjectOnboardingMetricsResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyContactRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingBuddyRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingBuddyServiceTest {
    private val onboardingBuddyRepository: OnboardingBuddyRepository = mockk(relaxed = true)
    private val buddyContactRepository: BuddyContactRepository = mockk(relaxed = true)
    private val projectMembershipApi: ProjectMembershipApi = mockk()
    private val onboardingMetricsService: OnboardingMetricsService = mockk()

    private val now: Instant = Instant.parse("2026-07-20T12:00:00Z")
    private val projectId: UUID = UUID.randomUUID()
    private val hireId: UUID = UUID.randomUUID()
    private val buddyId: UUID = UUID.randomUUID()

    private val service = OnboardingBuddyService(
        onboardingBuddyRepository,
        buddyContactRepository,
        projectMembershipApi,
        onboardingMetricsService,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    private fun members(joinedDaysAgo: Long = 30) = listOf(
        ProjectMember(hireId, "A Hire", "hire", daysAgo(joinedDaysAgo)),
        ProjectMember(buddyId, "A Buddy", "buddy", daysAgo(400)),
    )

    private fun timeline(
        stalled: Boolean = false,
        stalledReason: String? = null,
        longestOpenWaitHours: Long? = null,
    ) = HireTimelineResponse(
        userId = hireId,
        displayName = "A Hire",
        githubLogin = "hire",
        joinedAt = daysAgo(30),
        taskZeroAssignedAt = null,
        firstTaskClaimedAt = null,
        firstPullRequestOpenedAt = null,
        firstResponseAt = null,
        firstPullRequestMergedAt = null,
        hoursToFirstMergedPullRequest = null,
        hoursToFirstResponse = null,
        mergedPullRequestCount = 0,
        openPullRequestCount = 0,
        longestOpenWaitHours = longestOpenWaitHours,
        stalled = stalled,
        stalledReason = stalledReason,
        autonomyReachedAt = null,
        reworkedPullRequestCount = 0,
    )

    private fun stageAttention(
        hire: HireTimelineResponse = timeline(),
        assignments: List<OnboardingBuddy> = emptyList(),
        contacts: List<BuddyContact> = emptyList(),
    ) {
        every { projectMembershipApi.getProjectMembers(projectId) } returns members()
        every { onboardingBuddyRepository.findAllByProjectId(projectId) } returns assignments
        every { buddyContactRepository.findAllByProjectId(projectId) } returns contacts
        every { onboardingMetricsService.getProjectMetrics(projectId) } returns
            ProjectOnboardingMetricsResponse(
                projectId = projectId,
                memberCount = 2,
                unattributableMemberCount = 0,
                hiresWithMergedPullRequest = 0,
                medianHoursToFirstMergedPullRequest = null,
                medianHoursToFirstResponse = null,
                p90HoursToFirstResponse = null,
                stalledCount = 0,
                waitingOnResponseCount = 0,
                hires = listOf(hire),
            )
    }

    private fun assignment(cadenceDays: Int = 7, assignedDaysAgo: Long = 20) = OnboardingBuddy(
        hireId = hireId,
        projectId = projectId,
        buddyId = buddyId,
        cadenceTargetDays = cadenceDays,
        assignedAt = daysAgo(assignedDaysAgo),
    )

    @Nested
    inner class Assigning {
        @Test
        fun `pairs two members of the project`() {
            every { projectMembershipApi.getProjectMembers(projectId) } returns members()
            every { onboardingBuddyRepository.findByHireIdAndProjectId(hireId, projectId) } returns null
            every { onboardingBuddyRepository.save(any()) } answers { firstArg() }
            every {
                buddyContactRepository.findAllByHireIdAndProjectIdOrderByOccurredAtDesc(hireId, projectId)
            } returns emptyList()

            val result = service.assign(projectId, hireId, buddyId, null)

            assertEquals("A Buddy", result.buddyName)
            assertEquals(OnboardingBuddy.DEFAULT_CADENCE_TARGET_DAYS, result.cadenceTargetDays)
            assertNull(result.lastContactAt)
        }

        @Test
        fun `refuses to make somebody their own buddy`() {
            val error = assertThrows<ResponseStatusException> {
                service.assign(projectId, hireId, hireId, null)
            }

            // The entire point is that there is another person to ask.
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }

        @Test
        fun `refuses a buddy who is not on the project`() {
            every { projectMembershipApi.getProjectMembers(projectId) } returns
                listOf(ProjectMember(hireId, "A Hire", "hire", daysAgo(1)))

            val error = assertThrows<ResponseStatusException> {
                service.assign(projectId, hireId, buddyId, null)
            }

            // A buddy who cannot see the work is not a buddy.
            assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        }

        @Test
        fun `reassigning replaces the pairing rather than creating a second one`() {
            val existing = assignment()
            every { projectMembershipApi.getProjectMembers(projectId) } returns members()
            every { onboardingBuddyRepository.findByHireIdAndProjectId(hireId, projectId) } returns existing
            every {
                buddyContactRepository.findAllByHireIdAndProjectIdOrderByOccurredAtDesc(hireId, projectId)
            } returns emptyList()

            service.assign(projectId, hireId, buddyId, cadenceTargetDays = 3)

            assertEquals(3, existing.cadenceTargetDays)
            verify(exactly = 0) { onboardingBuddyRepository.save(any()) }
        }
    }

    @Nested
    inner class Contacts {
        @Test
        fun `records who logged it, because either side may`() {
            every { projectMembershipApi.getProjectMembers(projectId) } returns members()
            val saved = slot<BuddyContact>()
            every { buddyContactRepository.save(capture(saved)) } answers { firstArg() }

            service.logContact(projectId, hireId, recordedBy = buddyId, occurredAt = null, note = " spoke ")

            assertEquals(buddyId, saved.captured.recordedBy)
            assertEquals(hireId, saved.captured.hireId)
            assertEquals("spoke", saved.captured.note)
        }

        @Test
        fun `refuses a conversation dated in the future`() {
            every { projectMembershipApi.getProjectMembers(projectId) } returns members()

            val error = assertThrows<ResponseStatusException> {
                service.logContact(projectId, hireId, buddyId, now.plus(Duration.ofDays(1)), null)
            }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }

        @Test
        fun `an empty note is stored as nothing rather than as whitespace`() {
            every { projectMembershipApi.getProjectMembers(projectId) } returns members()
            val saved = slot<BuddyContact>()
            every { buddyContactRepository.save(capture(saved)) } answers { firstArg() }

            service.logContact(projectId, hireId, buddyId, null, "   ")

            assertNull(saved.captured.note)
        }
    }

    @Nested
    inner class MyBuddy {
        @Test
        fun `a pairing made today is not overdue`() {
            every { onboardingBuddyRepository.findByHireIdAndProjectId(hireId, projectId) } returns
                assignment(assignedDaysAgo = 0)
            every { projectMembershipApi.getProjectMembers(projectId) } returns members()
            every {
                buddyContactRepository.findAllByHireIdAndProjectIdOrderByOccurredAtDesc(hireId, projectId)
            } returns emptyList()

            val result = service.getBuddyFor(hireId, projectId)!!

            // Counted from assignment, not from epoch — otherwise every new pairing starts overdue
            // and people learn to ignore the signal.
            assertEquals(0, result.daysSinceContact)
            assertFalse(result.overdue)
            // The buddy's handle rides along so the hire has a concrete way to reach them.
            assertEquals("buddy", result.buddyGithubLogin)
        }

        @Test
        fun `silence past the cadence is overdue`() {
            every { onboardingBuddyRepository.findByHireIdAndProjectId(hireId, projectId) } returns
                assignment(cadenceDays = 7, assignedDaysAgo = 30)
            every { projectMembershipApi.getProjectMembers(projectId) } returns members()
            every {
                buddyContactRepository.findAllByHireIdAndProjectIdOrderByOccurredAtDesc(hireId, projectId)
            } returns listOf(
                BuddyContact(hireId = hireId, projectId = projectId, recordedBy = buddyId, occurredAt = daysAgo(10)),
            )

            val result = service.getBuddyFor(hireId, projectId)!!

            assertEquals(10, result.daysSinceContact)
            assertTrue(result.overdue)
        }

        @Test
        fun `no buddy is null rather than an error`() {
            every { onboardingBuddyRepository.findByHireIdAndProjectId(hireId, projectId) } returns null

            assertNull(service.getBuddyFor(hireId, projectId))
        }
    }

    @Nested
    inner class Attention {
        @Test
        fun `a waiting pull request is somebody else's move, and says so`() {
            stageAttention(hire = timeline(longestOpenWaitHours = 96), assignments = listOf(assignment()))

            val item = service.getAttention(projectId).items.first()

            assertEquals(AttentionSeverity.BLOCKED, item.severity)
            assertTrue(item.ownedByBuddy)
            assertTrue(item.reason.contains("4 days"))
        }

        @Test
        fun `a hire with no buddy is an attention item, not a silent gap`() {
            stageAttention()

            val items = service.getAttention(projectId).items

            assertTrue(items.any { it.reason.contains("No buddy assigned") })
            // Nobody owes them a reply, because nobody has been asked to.
            assertFalse(items.first { it.reason.contains("No buddy assigned") }.ownedByBuddy)
        }

        @Test
        fun `a pairing that has never spoken is named differently from one that has gone quiet`() {
            stageAttention(assignments = listOf(assignment(cadenceDays = 7, assignedDaysAgo = 20)))

            val item = service.getAttention(projectId).items.first { it.ownedByBuddy }

            assertTrue(item.reason.contains("have not spoken yet"))
        }

        @Test
        fun `blocked sorts before drifting, and the longest wait first`() {
            stageAttention(
                hire = timeline(longestOpenWaitHours = 240, stalled = true, stalledReason = "Stalled"),
                assignments = listOf(assignment(assignedDaysAgo = 20)),
            )

            val severities = service.getAttention(projectId).items.map { it.severity }

            assertEquals(AttentionSeverity.BLOCKED, severities.first())
        }

        @Test
        fun `a healthy hire produces nothing to act on`() {
            stageAttention(
                assignments = listOf(assignment(cadenceDays = 7, assignedDaysAgo = 20)),
                contacts = listOf(
                    BuddyContact(
                        hireId = hireId,
                        projectId = projectId,
                        recordedBy = buddyId,
                        occurredAt = daysAgo(1),
                    ),
                ),
            )

            val attention = service.getAttention(projectId)

            assertTrue(attention.items.isEmpty())
            assertEquals(1, attention.withBuddyCount)
            assertEquals(1, attention.recentContactCount)
        }

        @Test
        fun `a stall the metrics found is carried through in its own words`() {
            stageAttention(
                hire = timeline(stalled = true, stalledReason = "No GitHub username on record"),
                assignments = listOf(assignment(assignedDaysAgo = 1)),
            )

            val items = service.getAttention(projectId).items

            assertTrue(items.any { it.reason == "No GitHub username on record" })
        }
    }

    @Nested
    inner class Mentees {
        @Test
        fun `a caller who mentors nobody gets an empty list, not an error`() {
            every { onboardingBuddyRepository.findAllByBuddyId(buddyId) } returns emptyList()

            assertTrue(service.getMenteesFor(buddyId).mentees.isEmpty())
        }

        @Test
        fun `a mentee on track is returned calm, with no alerts`() {
            every { onboardingBuddyRepository.findAllByBuddyId(buddyId) } returns
                listOf(assignment(cadenceDays = 7, assignedDaysAgo = 2))
            stageAttention()

            val mentee = service.getMenteesFor(buddyId).mentees.single()

            assertEquals(hireId, mentee.hireId)
            assertEquals("hire", mentee.hireGithubLogin)
            assertFalse(mentee.overdue)
            assertTrue(mentee.alerts.isEmpty())
        }

        @Test
        fun `a review kept waiting is surfaced as the buddy's move`() {
            every { onboardingBuddyRepository.findAllByBuddyId(buddyId) } returns
                listOf(assignment(assignedDaysAgo = 1))
            stageAttention(hire = timeline(longestOpenWaitHours = 72))

            val alerts = service
                .getMenteesFor(buddyId)
                .mentees
                .single()
                .alerts

            assertTrue(alerts.any { it.severity == AttentionSeverity.BLOCKED })
        }

        @Test
        fun `a cadence gone quiet is overdue and alerts`() {
            every { onboardingBuddyRepository.findAllByBuddyId(buddyId) } returns
                listOf(assignment(cadenceDays = 7, assignedDaysAgo = 20))
            stageAttention()

            val mentee = service.getMenteesFor(buddyId).mentees.single()

            assertTrue(mentee.overdue)
            assertTrue(mentee.alerts.any { it.severity == AttentionSeverity.DRIFTING })
        }

        @Test
        fun `mentees with something outstanding sort ahead of calm ones`() {
            val blockedHire = UUID.randomUUID()
            val calmHire = UUID.randomUUID()
            every { onboardingBuddyRepository.findAllByBuddyId(buddyId) } returns listOf(
                OnboardingBuddy(
                    hireId = calmHire,
                    projectId = projectId,
                    buddyId = buddyId,
                    cadenceTargetDays = 7,
                    assignedAt = daysAgo(1),
                ),
                OnboardingBuddy(
                    hireId = blockedHire,
                    projectId = projectId,
                    buddyId = buddyId,
                    cadenceTargetDays = 7,
                    assignedAt = daysAgo(1),
                ),
            )
            every { projectMembershipApi.getProjectMembers(projectId) } returns listOf(
                ProjectMember(blockedHire, "Blocked Hire", "blocked", daysAgo(30)),
                ProjectMember(calmHire, "Calm Hire", "calm", daysAgo(30)),
                ProjectMember(buddyId, "A Buddy", "buddy", daysAgo(400)),
            )
            every { buddyContactRepository.findAllByProjectId(projectId) } returns emptyList()
            every { onboardingMetricsService.getProjectMetrics(projectId) } returns
                ProjectOnboardingMetricsResponse(
                    projectId = projectId,
                    memberCount = 3,
                    unattributableMemberCount = 0,
                    hiresWithMergedPullRequest = 0,
                    medianHoursToFirstMergedPullRequest = null,
                    medianHoursToFirstResponse = null,
                    p90HoursToFirstResponse = null,
                    stalledCount = 0,
                    waitingOnResponseCount = 0,
                    hires = listOf(
                        timeline(longestOpenWaitHours = 72).copy(userId = blockedHire, displayName = "Blocked Hire"),
                        timeline().copy(userId = calmHire, displayName = "Calm Hire"),
                    ),
                )

            val mentees = service.getMenteesFor(buddyId).mentees

            assertEquals(blockedHire, mentees.first().hireId)
            assertTrue(mentees.last().alerts.isEmpty())
        }
    }
}
