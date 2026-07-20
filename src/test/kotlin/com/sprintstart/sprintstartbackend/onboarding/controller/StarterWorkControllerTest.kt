package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.GenerateStarterWorkResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.ProposedStarterWorkResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.RankedStarterWorkTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkTaskProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.service.StarterWorkTaskProposalService
import com.sprintstart.sprintstartbackend.onboarding.service.UserGoalService
import io.mockk.coEvery
import io.mockk.every
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

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
    fun `getMatchesForMe should return 200 for a USER`() {
        coEvery { starterWorkTaskProposalService.matchForUser("test-subject") } returns
            listOf(
                RankedStarterWorkTaskResponse(
                    task = taskResponse(),
                    score = 1.0,
                    matchedCompetencyKeys = listOf("docs"),
                ),
            )

        // Suspend controller methods dispatch asynchronously in Spring MVC; the final status is
        // only observable after asyncDispatch, mirroring VerificationControllerTest's
        // `synthesizeContent` test.
        val mvcResult = mockMvc
            .perform(get("/api/v1/onboarding/starter-work/me/matches").with(userJwt))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc
            .perform(asyncDispatch(mvcResult))
            .andExpect(status().isOk)
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
}
