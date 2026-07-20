package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.ingestion.external.AuthoredPullRequest
import com.sprintstart.sprintstartbackend.onboarding.model.entity.EnvironmentEvidence
import com.sprintstart.sprintstartbackend.onboarding.model.entity.EnvironmentReadiness
import com.sprintstart.sprintstartbackend.onboarding.repository.EnvironmentReadinessRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

class EnvironmentReadinessServiceTest {
    private val repository: EnvironmentReadinessRepository = mockk(relaxed = true)
    private val projectMembershipApi: ProjectMembershipApi = mockk()
    private val artifactIngestionApi: ArtifactIngestionApi = mockk()

    private val now: Instant = Instant.parse("2026-07-20T12:00:00Z")
    private val hireId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()

    private val service = EnvironmentReadinessService(
        repository,
        projectMembershipApi,
        artifactIngestionApi,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun member(login: String? = "hire") =
        ProjectMember(hireId, "A Hire", login, now.minus(Duration.ofDays(5)))

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    private fun isMember(login: String? = "hire") {
        every { projectMembershipApi.getProjectMembers(projectId) } returns listOf(member(login))
    }

    private fun pr(openedAt: Instant?) =
        AuthoredPullRequest(UUID.randomUUID(), openedAt, null, null, "OPEN")

    @Test
    fun `report records a build-test run as readiness`() {
        isMember()
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns null
        every { repository.save(any()) } answers { firstArg() }

        val result = service.report(hireId, projectId, EnvironmentEvidence.BUILD_TEST_RUN, null, "all green")

        assertTrue(result.ready)
        assertEquals(EnvironmentEvidence.BUILD_TEST_RUN, result.evidence)
        assertEquals(now, result.readyAt)
        assertFalse(result.derived)
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `report is idempotent — the first evidence wins and is not overwritten`() {
        isMember()
        val existing = EnvironmentReadiness(
            hireId = hireId,
            projectId = projectId,
            readyAt = daysAgo(2),
            evidence = EnvironmentEvidence.GREEN_CI,
        )
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns existing

        val result = service.report(hireId, projectId, EnvironmentEvidence.BUILD_TEST_RUN, null, null)

        assertEquals(EnvironmentEvidence.GREEN_CI, result.evidence)
        assertEquals(daysAgo(2), result.readyAt)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `report rejects the derived-only evidence type`() {
        isMember()
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns null

        assertThrows<ResponseStatusException> {
            service.report(hireId, projectId, EnvironmentEvidence.PULL_REQUEST, null, null)
        }
    }

    @Test
    fun `report rejects a future timestamp`() {
        isMember()
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns null

        assertThrows<ResponseStatusException> {
            service.report(hireId, projectId, EnvironmentEvidence.BUILD_TEST_RUN, now.plusSeconds(3600), null)
        }
    }

    @Test
    fun `report 404s when the hire is not a member of the project`() {
        every { projectMembershipApi.getProjectMembers(projectId) } returns emptyList()

        assertThrows<ResponseStatusException> {
            service.report(hireId, projectId, EnvironmentEvidence.BUILD_TEST_RUN, null, null)
        }
    }

    @Test
    fun `getReadiness returns the stored evidence`() {
        isMember()
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns
            EnvironmentReadiness(
                hireId = hireId,
                projectId = projectId,
                readyAt = daysAgo(1),
                evidence = EnvironmentEvidence.GREEN_CI,
                evidenceDetail = "https://ci/run/1",
            )

        val result = service.getReadiness(hireId, projectId)

        assertTrue(result.ready)
        assertEquals(EnvironmentEvidence.GREEN_CI, result.evidence)
        assertFalse(result.derived)
    }

    @Test
    fun `getReadiness derives readiness from an authored pull request when nothing was reported`() {
        // The issue's headline case: readiness that arrives via committed work without the command.
        isMember()
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns null
        every { artifactIngestionApi.getAuthoredPullRequests(projectId, "hire") } returns
            listOf(pr(daysAgo(3)), pr(daysAgo(1)))

        val result = service.getReadiness(hireId, projectId)

        assertTrue(result.ready)
        assertTrue(result.derived)
        assertEquals(EnvironmentEvidence.PULL_REQUEST, result.evidence)
        // Earliest authored PR is when they demonstrably had a working environment.
        assertEquals(daysAgo(3), result.readyAt)
    }

    @Test
    fun `getReadiness is not-ready when there is neither evidence nor an authored pull request`() {
        isMember()
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns null
        every { artifactIngestionApi.getAuthoredPullRequests(projectId, "hire") } returns emptyList()

        val result = service.getReadiness(hireId, projectId)

        assertFalse(result.ready)
        assertNull(result.readyAt)
        assertNull(result.evidence)
    }

    @Test
    fun `getReadiness cannot derive readiness for a hire with no declared github login`() {
        isMember(login = null)
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns null

        val result = service.getReadiness(hireId, projectId)

        assertFalse(result.ready)
    }

    @Test
    fun `readyAtFor prefers stored evidence over the derived pull-request moment`() {
        val stored = daysAgo(1)
        every { repository.findByHireIdAndProjectId(hireId, projectId) } returns
            EnvironmentReadiness(
                hireId = hireId,
                projectId = projectId,
                readyAt = stored,
                evidence = EnvironmentEvidence.BUILD_TEST_RUN,
            )

        assertEquals(stored, service.readyAtFor(member(), projectId))
    }
}
