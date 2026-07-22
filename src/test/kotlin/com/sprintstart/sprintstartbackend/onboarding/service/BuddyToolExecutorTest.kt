package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.MyCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.HireTimelineResponse
import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.external.dto.ProjectDto
import com.sprintstart.sprintstartbackend.user.external.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BuddyToolExecutorTest {
    private val onboardingMetricsService: OnboardingMetricsService = mockk()
    private val myCompetencyService: MyCompetencyService = mockk()
    private val userApi: UserApi = mockk()
    private val executor = BuddyToolExecutor(onboardingMetricsService, myCompetencyService, userApi)

    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val metricsCall = BuddyToolCallDto(id = "c0", name = "get_my_metrics")
    private val competenciesCall = BuddyToolCallDto(id = "c0", name = "get_my_competencies")

    private fun userWith(vararg projects: ProjectDto) = UserDto(
        id = userId,
        username = "hire",
        firstname = "Sam",
        lastname = "Hire",
        avatarUrl = null,
        profileIcon = null,
        projects = projects.toSet(),
        projectRoles = emptyList(),
    )

    private fun timeline(
        openPrs: Int = 1,
        longestOpenWaitHours: Long? = 52,
        stalled: Boolean = true,
        stalledReason: String? = "waiting on a review",
    ) = HireTimelineResponse(
        userId = userId,
        displayName = "Sam Hire",
        githubLogin = "sam",
        joinedAt = null,
        taskZeroAssignedAt = null,
        firstTaskClaimedAt = null,
        firstPullRequestOpenedAt = null,
        firstResponseAt = null,
        firstPullRequestMergedAt = null,
        hoursToFirstMergedPullRequest = null,
        hoursToFirstResponse = null,
        mergedPullRequestCount = 0,
        openPullRequestCount = openPrs,
        longestOpenWaitHours = longestOpenWaitHours,
        stalled = stalled,
        stalledReason = stalledReason,
        autonomyReachedAt = null,
        reworkedPullRequestCount = 0,
    )

    private fun competency(
        label: String,
        level: Int,
        targetLevel: Int,
    ) = MyCompetencyResponse(
        competencyKey = label.lowercase(),
        label = label,
        kind = CompetencyKind.SKILL,
        level = level,
        targetLevel = targetLevel,
        source = CompetencySource.VERIFIED,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `exposes the caller-scoped hire-state tools`() {
        assertThat(executor.toolSpecs().map { it.name })
            .containsExactly("get_my_metrics", "get_my_competencies")
    }

    @Test
    fun `describes the wait and stall for a project the hire is on`() {
        every { userApi.getUsersByIds(listOf(userId)) } returns
            listOf(userWith(ProjectDto(projectId, "Checkout", null)))
        every { onboardingMetricsService.getHireTimeline(userId, projectId) } returns timeline()

        val result = executor.execute(metricsCall, userId)

        assertThat(result).contains("Checkout")
        assertThat(result).contains("52 hours")
        assertThat(result).contains("waiting on a review")
    }

    @Test
    fun `says so plainly when the hire is on no project`() {
        every { userApi.getUsersByIds(listOf(userId)) } returns listOf(userWith())

        val result = executor.execute(metricsCall, userId)

        assertThat(result).contains("not a member of any project")
    }

    @Test
    fun `separates held competencies from those still below target`() {
        every { myCompetencyService.getCompetenciesForUser(userId) } returns listOf(
            competency("Kotlin", level = 3, targetLevel = 2),
            competency("React", level = 1, targetLevel = 3),
        )

        val result = executor.execute(competenciesCall, userId)

        assertThat(result).contains("Competencies held (meet their target level): 1")
        assertThat(result).contains("Kotlin (level 3/2)")
        assertThat(result).contains("Below target")
        assertThat(result).contains("React (level 1/3)")
    }

    @Test
    fun `excludes level-0 placed-but-unknown rows from the ledger`() {
        every { myCompetencyService.getCompetenciesForUser(userId) } returns listOf(
            competency("Docker", level = 0, targetLevel = 2),
        )

        val result = executor.execute(competenciesCall, userId)

        assertThat(result).contains("no demonstrated competencies")
    }

    @Test
    fun `reports an unknown tool rather than throwing`() {
        val result = executor.execute(BuddyToolCallDto(id = "c1", name = "launch_rockets"), userId)

        assertThat(result).contains("Unknown tool")
    }
}
