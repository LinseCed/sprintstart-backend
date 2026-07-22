package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContentProvenance
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModulePageKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModuleStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyModule
import com.sprintstart.sprintstartbackend.onboarding.model.entity.ModulePage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.ModulePageCitation
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GoalView
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathEdge
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathNode
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyModuleRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.external.dto.ProjectDto
import com.sprintstart.sprintstartbackend.user.external.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class BuddyPlanToolsTest {
    private val competencyPathService: CompetencyPathService = mockk()
    private val competencyModuleRepository: CompetencyModuleRepository = mockk()
    private val verificationRepository: VerificationRepository = mockk()
    private val userApi: UserApi = mockk()
    private val tools = BuddyPlanTools(
        competencyPathService,
        competencyModuleRepository,
        verificationRepository,
        userApi,
    )

    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val planCall = BuddyToolCallDto(id = "c0", name = "get_learning_plan")

    private fun moduleCall(competencyKey: String) = BuddyToolCallDto(
        id = "c0",
        name = "get_module",
        arguments = buildJsonObject { put("competency_key", competencyKey) },
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

    private fun onOneProject() {
        every { userApi.getUsersByIds(listOf(userId)) } returns
            listOf(userWith(ProjectDto(projectId, "Checkout", null)))
    }

    private fun node(
        key: String,
        label: String,
        state: NodeState,
        level: Int?,
        targetLevel: Int = 2,
        moduleId: UUID? = null,
    ) = PathNode(
        key = key,
        label = label,
        kind = CompetencyKind.SKILL,
        state = state,
        level = level,
        targetLevel = targetLevel,
        moduleId = moduleId,
    )

    @Test
    fun `exposes the learning-plan and module tools`() {
        assertThat(tools.toolSpecs().map { it.name }).containsExactly("get_learning_plan", "get_module")
        assertThat(tools.handles("get_learning_plan")).isTrue()
        assertThat(tools.handles("get_my_metrics")).isFalse()
    }

    @Test
    fun `reads the plan in graph order with reasons, levels, and module availability`() {
        onOneProject()
        val reactModuleId = UUID.randomUUID()
        every { competencyPathService.getPathForUser(userId, projectId) } returns PathView(
            nodes = listOf(
                node("kotlin", "Kotlin", NodeState.MASTERED, level = 2),
                node("react", "React", NodeState.AVAILABLE, level = 0, moduleId = reactModuleId),
                node("testing", "Testing", NodeState.AVAILABLE, level = 1, targetLevel = 3),
                node("ci", "CI pipelines", NodeState.AVAILABLE, level = 0),
                node("docker", "Docker", NodeState.AVAILABLE, level = 0),
            ),
            edges = listOf(
                PathEdge(from = "kotlin", to = "react"),
                PathEdge(from = "react", to = "testing"),
                PathEdge(from = "testing", to = "ci"),
                PathEdge(from = "ci", to = "docker"),
            ),
            graphVersion = 1,
        )
        every { competencyModuleRepository.findAllByProjectIdAndStatus(projectId, ModuleStatus.ACTIVE) } returns
            listOf(
                CompetencyModule(
                    competencyKey = "react",
                    projectId = projectId,
                    version = 1,
                    status = ModuleStatus.ACTIVE,
                    title = "React basics",
                    id = reactModuleId,
                ),
            )

        val result = tools.execute(planCall, userId)

        assertThat(result).contains("Learning plan on Checkout")
        assertThat(result).contains("the team's baseline")
        assertThat(result).contains("React (level 0/2) — usually comes after Kotlin. Module: “React basics”")
        assertThat(result).contains("Testing (level 1/3) — usually comes after React. No published module yet")
        assertThat(result).contains("After that:")
        assertThat(result).contains("Docker (level 0/2) — usually comes after CI pipelines")
        assertThat(result).contains("Already met: Kotlin")
        // Reasons travel; no score or internal state name does.
        assertThat(result).doesNotContain("AVAILABLE")
    }

    @Test
    fun `names the goal and what stands between the hire and it`() {
        onOneProject()
        every { competencyPathService.getPathForUser(userId, projectId) } returns PathView(
            nodes = listOf(node("kotlin", "Kotlin", NodeState.AVAILABLE, level = 0)),
            edges = emptyList(),
            graphVersion = 1,
            goal = GoalView(
                competencyKey = "contrib-fix-login",
                label = "Fix the login redirect",
                remainingCount = 2,
                isReachable = false,
            ),
        )
        every { competencyModuleRepository.findAllByProjectIdAndStatus(projectId, ModuleStatus.ACTIVE) } returns
            emptyList()

        val result = tools.execute(planCall, userId)

        assertThat(result).contains("Working toward: Fix the login redirect — 2 prerequisite(s) still to go.")
    }

    @Test
    fun `an empty plan is an answer, not an error`() {
        onOneProject()
        every { competencyPathService.getPathForUser(userId, projectId) } returns
            PathView(nodes = emptyList(), edges = emptyList(), graphVersion = 1)
        every { competencyModuleRepository.findAllByProjectIdAndStatus(projectId, ModuleStatus.ACTIVE) } returns
            emptyList()

        val result = tools.execute(planCall, userId)

        assertThat(result).contains("no learning plan yet")
    }

    @Test
    fun `says so plainly when the hire is on no project`() {
        every { userApi.getUsersByIds(listOf(userId)) } returns listOf(userWith())

        assertThat(tools.execute(planCall, userId)).contains("not a member of any project")
    }

    @Test
    fun `teaches a published module with its pages, citations, and check prompt — never its rubric`() {
        onOneProject()
        val module = CompetencyModule(
            competencyKey = "react",
            projectId = projectId,
            version = 2,
            status = ModuleStatus.ACTIVE,
            title = "React basics",
            summary = "Components and state, grounded in our repo.",
        )
        val lesson = ModulePage(
            module = module,
            kind = ModulePageKind.LESSON,
            title = "How our components work",
            body = "Every screen composes small components.",
            position = 0,
            provenance = ContentProvenance.AI,
        )
        lesson.citations.add(
            ModulePageCitation(
                page = lesson,
                filename = "docs/frontend.md",
                chunkId = "chunk-1",
                sourceUrl = "https://example.test/docs/frontend",
                position = 0,
            ),
        )
        module.pages.add(lesson)
        module.pages.add(
            ModulePage(
                module = module,
                kind = ModulePageKind.TASK,
                title = "Build a greeting card",
                body = "Add a component that greets by name.",
                position = 1,
                provenance = ContentProvenance.AI,
            ),
        )
        every {
            competencyModuleRepository.findByCompetencyKeyAndProjectIdAndStatus("react", projectId, ModuleStatus.ACTIVE)
        } returns module
        every { verificationRepository.findByModuleId(module.id) } returns Verification(
            moduleId = module.id,
            type = VerificationType.ARTIFACT,
            prompt = "Open a PR adding the greeting component",
            rubric = "SECRET RUBRIC",
            competencyKey = "react",
            level = "intermediate",
        )

        val result = tools.execute(moduleCall("react"), userId)

        assertThat(result).contains("Module “React basics”")
        assertThat(result).contains("[LESSON] How our components work")
        assertThat(result).contains("Every screen composes small components.")
        assertThat(result).contains("docs/frontend.md (https://example.test/docs/frontend)")
        assertThat(result).contains("Check to pass (ARTIFACT): “Open a PR adding the greeting component”")
        // The rubric is what the hire is graded against — it never travels to the buddy.
        assertThat(result).doesNotContain("SECRET RUBRIC")
    }

    @Test
    fun `reports no published module so the buddy falls back to the docs`() {
        onOneProject()
        every {
            competencyModuleRepository.findByCompetencyKeyAndProjectIdAndStatus("react", projectId, ModuleStatus.ACTIVE)
        } returns null

        val result = tools.execute(moduleCall("react"), userId)

        assertThat(result).contains("No published module teaches 'react'")
        assertThat(result).contains("search_docs")
    }
}
