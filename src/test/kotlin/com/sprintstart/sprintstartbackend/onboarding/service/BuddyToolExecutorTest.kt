package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.TaskType
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CanonicalAnswer
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.MyCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.HireTimelineResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.RankedStarterWorkTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkTaskProposalResponse
import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.external.dto.ProjectDto
import com.sprintstart.sprintstartbackend.user.external.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BuddyToolExecutorTest {
    private val onboardingMetricsService: OnboardingMetricsService = mockk()
    private val myCompetencyService: MyCompetencyService = mockk()
    private val starterWorkTaskProposalService: StarterWorkTaskProposalService = mockk()
    private val knowledgeBaseService: KnowledgeBaseService = mockk()
    private val userApi: UserApi = mockk()
    private val executor = BuddyToolExecutor(
        onboardingMetricsService,
        myCompetencyService,
        starterWorkTaskProposalService,
        knowledgeBaseService,
        userApi,
    )

    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val metricsCall = BuddyToolCallDto(id = "c0", name = "get_my_metrics")
    private val competenciesCall = BuddyToolCallDto(id = "c0", name = "get_my_competencies")
    private val suggestedTasksCall = BuddyToolCallDto(id = "c0", name = "get_suggested_tasks")
    private fun canonicalSearchCall(query: String) = BuddyToolCallDto(
        id = "c0",
        name = "search_canonical_answers",
        arguments = buildJsonObject { put("query", query) },
    )

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

    private fun rankedTask(
        title: String,
        reasons: List<String>,
        sourceUrl: String? = null,
    ) = RankedStarterWorkTaskResponse(
        task = StarterWorkTaskProposalResponse(
            id = UUID.randomUUID(),
            sourceId = "src-$title",
            title = title,
            summary = null,
            rationale = null,
            sourceUrl = sourceUrl,
            competencyKeys = emptyList(),
            status = ProposalStatus.APPROVED,
            taskZeroEligible = false,
        ),
        score = 1.0,
        matchedCompetencyKeys = emptyList(),
        taskType = TaskType.BUG,
        reasons = reasons,
    )

    @Test
    fun `exposes the caller-scoped hire-state tools`() {
        assertThat(executor.toolSpecs().map { it.name }).containsExactly(
            "get_my_metrics",
            "get_my_competencies",
            "get_suggested_tasks",
            "search_canonical_answers",
        )
    }

    @Test
    fun `serves a teammate's canonical answer faithfully`() {
        every { knowledgeBaseService.searchForUser(userId, "how do we deploy") } returns listOf(
            CanonicalAnswer(
                projectId = projectId,
                question = "How do we deploy?",
                answer = "Run ./deploy.sh from main after CI is green.",
                authorId = UUID.randomUUID(),
            ),
        )

        val result = executor.execute(canonicalSearchCall("how do we deploy"), userId)

        assertThat(result).contains("How do we deploy?")
        assertThat(result).contains("Run ./deploy.sh from main after CI is green.")
    }

    @Test
    fun `reports no canonical answer so the buddy knows to suggest escalation`() {
        every { knowledgeBaseService.searchForUser(userId, "obscure thing") } returns emptyList()

        val result = executor.execute(canonicalSearchCall("obscure thing"), userId)

        assertThat(result).contains("No teammate has answered")
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
    fun `suggests ranked tasks with their reasons and never a score`() {
        every { userApi.getUsersByIds(listOf(userId)) } returns
            listOf(userWith(ProjectDto(projectId, "Checkout", null)))
        every { starterWorkTaskProposalService.matchForUserId(userId, projectId) } returns listOf(
            rankedTask(
                "Fix the login redirect",
                reasons = listOf("Matches a skill you hold (Kotlin)", "Labelled 'good first issue'"),
                sourceUrl = "https://example.test/issues/1",
            ),
        )

        val result = executor.execute(suggestedTasksCall, userId)

        assertThat(result).contains("Checkout")
        assertThat(result).contains("Fix the login redirect")
        assertThat(result).contains("Matches a skill you hold (Kotlin)")
        assertThat(result).contains("https://example.test/issues/1")
        // The ranker's score must never surface — a number is not a reason a hire can act on.
        assertThat(result).doesNotContain("1.0")
    }

    @Test
    fun `says so when there are no approved tasks to suggest`() {
        every { userApi.getUsersByIds(listOf(userId)) } returns
            listOf(userWith(ProjectDto(projectId, "Checkout", null)))
        every { starterWorkTaskProposalService.matchForUserId(userId, projectId) } returns emptyList()

        val result = executor.execute(suggestedTasksCall, userId)

        assertThat(result).contains("no approved starter-work tasks")
    }

    @Test
    fun `reports an unknown tool rather than throwing`() {
        val result = executor.execute(BuddyToolCallDto(id = "c1", name = "launch_rockets"), userId)

        assertThat(result).contains("Unknown tool")
    }
}
