package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.TaskType
import com.sprintstart.sprintstartbackend.onboarding.external.model.AiProgressEvent
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.GenerateStarterWorkResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.ProposedStarterWorkResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.RankedStarterWorkTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkTaskProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.service.StarterWorkTaskProposalService
import com.sprintstart.sprintstartbackend.onboarding.service.UserGoalService
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertTrue

@WebMvcTest(StarterWorkController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class StarterWorkControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var starterWorkTaskProposalService: StarterWorkTaskProposalService

    @MockkBean
    private lateinit var userGoalService: UserGoalService

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val objectMapper = jacksonObjectMapper()

    private fun jwtWithRoles(vararg roles: String): JwtRequestPostProcessor {
        return jwt()
            .jwt { jwt ->
                jwt.subject("test-subject")
                jwt.claim("realm_access", mapOf("roles" to roles.toList()))
            }.authorities(roles.map { SimpleGrantedAuthority("ROLE_$it") })
    }

    private val pmJwt = jwtWithRoles("PM")
    private val userJwt = jwtWithRoles("USER")

    private val taskId = UUID.randomUUID()

    private fun taskResponse(): StarterWorkTaskProposalResponse =
        StarterWorkTaskProposalResponse(
            id = taskId,
            sourceId = "github:org/repo:ISSUE:1",
            title = "Fix typo",
            summary = "Fix a typo in the README.",
            rationale = "Small, well-scoped.",
            sourceUrl = "https://github.com/org/repo/issues/1",
            competencyKeys = listOf("docs"),
            status = ProposalStatus.APPROVED,
            taskZeroEligible = false,
        )

    @Test
    fun `listProposed should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/starter-work/proposed"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `listProposed should return 200 for a PM`() {
        every { starterWorkTaskProposalService.listProposed() } returns
            ProposedStarterWorkResponse(tasks = emptyList())

        mockMvc
            .perform(get("/api/v1/onboarding/starter-work/proposed").with(pmJwt))
            .andExpect(status().isOk)
    }

    @Test
    fun `listProposed should return 403 for a plain USER`() {
        mockMvc
            .perform(get("/api/v1/onboarding/starter-work/proposed").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `listApproved should return 200 for a PM`() {
        every { starterWorkTaskProposalService.listApproved() } returns listOf(taskResponse())

        mockMvc
            .perform(get("/api/v1/onboarding/starter-work/approved").with(pmJwt))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].status").value("APPROVED"))
    }

    @Test
    fun `listApproved should return 403 for a plain USER`() {
        mockMvc
            .perform(get("/api/v1/onboarding/starter-work/approved").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `create should return 200 and the created task for a PM`() {
        every { starterWorkTaskProposalService.createTask(any()) } returns taskResponse()

        mockMvc
            .perform(
                post("/api/v1/onboarding/starter-work")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "Add dark mode"))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
    }

    @Test
    fun `create should return 403 for a plain USER`() {
        mockMvc
            .perform(
                post("/api/v1/onboarding/starter-work")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("title" to "Add dark mode"))),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `approve should return 200 for a PM`() {
        every { starterWorkTaskProposalService.approve(taskId) } returns taskResponse()

        mockMvc
            .perform(post("/api/v1/onboarding/starter-work/$taskId/approve").with(pmJwt))
            .andExpect(status().isOk)
    }

    @Test
    fun `approve should return 403 for a plain USER`() {
        mockMvc
            .perform(post("/api/v1/onboarding/starter-work/$taskId/approve").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `reject should return 200 for a PM`() {
        every { starterWorkTaskProposalService.reject(taskId, "not relevant") } returns taskResponse()

        mockMvc
            .perform(
                post("/api/v1/onboarding/starter-work/$taskId/reject")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("reason" to "not relevant"))),
            ).andExpect(status().isOk)
    }

    @Test
    fun `getMatchesForMe should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/starter-work/me/matches"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `getMatchesForMe should return 200 for a USER, with the reasons a task was suggested`() {
        val projectId = UUID.randomUUID()
        // No longer a suspend method: ranking is deterministic and local since #74, so there is no
        // AI call to dispatch asynchronously around.
        every { starterWorkTaskProposalService.matchForUser("test-subject", projectId) } returns
            listOf(
                RankedStarterWorkTaskResponse(
                    task = taskResponse(),
                    score = 40.0,
                    matchedCompetencyKeys = listOf("docs"),
                    taskType = TaskType.DOCS,
                    reasons = listOf("uses docs, which you have already shown"),
                ),
            )

        mockMvc
            .perform(
                get("/api/v1/onboarding/starter-work/me/matches")
                    .param("projectId", projectId.toString())
                    .with(userJwt),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].taskType").value("DOCS"))
            .andExpect(jsonPath("$[0].reasons[0]").value("uses docs, which you have already shown"))
    }

    @Test
    fun `generate should return 401 when not authenticated`() {
        mockMvc
            .perform(post("/api/v1/onboarding/starter-work/generate"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `generate should return 403 for a plain USER`() {
        val mvcResult = mockMvc
            .perform(post("/api/v1/onboarding/starter-work/generate").with(userJwt))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc
            .perform(asyncDispatch(mvcResult))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `generate should return 200 for a PM`() {
        coEvery { starterWorkTaskProposalService.generate() } returns
            GenerateStarterWorkResponse(status = "proposed", tasksProposed = 1, notes = emptyList())

        val mvcResult = mockMvc
            .perform(post("/api/v1/onboarding/starter-work/generate").with(pmJwt))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc
            .perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
    }

    @Test
    fun `streamGenerate should return 403 for a plain USER`() {
        val mvcResult = mockMvc
            .perform(post("/api/v1/onboarding/starter-work/generate/stream").with(userJwt))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc
            .perform(asyncDispatch(mvcResult))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `streamGenerate streams progress events for a PM`() {
        coEvery { starterWorkTaskProposalService.streamGenerate() } returns
            flowOf(
                AiProgressEvent(type = "stage", operation = "starter_work", stage = "retrieving", label = "…"),
                AiProgressEvent(type = "item", operation = "starter_work", label = "Task: Fix typo"),
                AiProgressEvent(type = "done", operation = "starter_work", label = "Proposed 1"),
            )

        val mvcResult = mockMvc
            .perform(post("/api/v1/onboarding/starter-work/generate/stream").with(pmJwt))
            .andExpect(request().asyncStarted())
            .andReturn()

        val body = mockMvc
            .perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        assertTrue(body.contains("\"type\":\"item\""))
        assertTrue(body.contains("\"type\":\"done\""))
    }
}
