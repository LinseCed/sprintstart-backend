package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.ApproveGraphBatchResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyEdgeProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.ProposedCompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphAuthoringService
import com.sprintstart.sprintstartbackend.onboarding.service.CompetencyProposalService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(CompetencyGraphProposalController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class CompetencyGraphProposalControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var competencyProposalService: CompetencyProposalService

    @MockkBean
    private lateinit var competencyGraphAuthoringService: CompetencyGraphAuthoringService

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

    private val competencyId = UUID.randomUUID()
    private val edgeId = UUID.randomUUID()

    private fun competencyResponse(): CompetencyProposalResponse =
        CompetencyProposalResponse(
            id = competencyId,
            key = "kotlin",
            label = "Kotlin",
            description = null,
            kind = CompetencyKind.SKILL,
            repoRef = null,
            status = ProposalStatus.APPROVED,
        )

    private fun liveCompetencyResponse(): CompetencyResponse =
        CompetencyResponse(
            key = "kotlin",
            label = "Kotlin Basics",
            description = null,
            kind = CompetencyKind.SKILL,
            targetLevel = 3,
            invariant = false,
            repoRef = null,
        )

    private fun edgeResponse(): CompetencyEdgeProposalResponse =
        CompetencyEdgeProposalResponse(
            id = edgeId,
            fromKey = "kotlin",
            toKey = "our-domain-model",
            kind = EdgeKind.PREREQUISITE,
            rationale = null,
            status = ProposalStatus.APPROVED,
        )

    @Test
    fun `generate should return 401 when not authenticated`() {
        mockMvc
            .perform(post("/api/v1/onboarding/competency-graph/generate"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `listProposed should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/competency-graph/proposed"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `listProposed should return 200 for a PM`() {
        every { competencyProposalService.listProposed() } returns
            ProposedCompetencyGraphResponse(competencies = emptyList(), edges = emptyList())

        mockMvc
            .perform(get("/api/v1/onboarding/competency-graph/proposed").with(pmJwt))
            .andExpect(status().isOk)
    }

    @Test
    fun `listProposed should return 403 for a plain USER`() {
        mockMvc
            .perform(get("/api/v1/onboarding/competency-graph/proposed").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `approveCompetency should return 200 for a PM`() {
        every { competencyProposalService.approveCompetency(competencyId) } returns competencyResponse()

        mockMvc
            .perform(post("/api/v1/onboarding/competency-graph/competencies/$competencyId/approve").with(pmJwt))
            .andExpect(status().isOk)
    }

    @Test
    fun `approveCompetency should return 403 for a plain USER`() {
        mockMvc
            .perform(post("/api/v1/onboarding/competency-graph/competencies/$competencyId/approve").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `rejectCompetency should return 200 for a PM`() {
        every { competencyProposalService.rejectCompetency(competencyId, "not relevant") } returns
            competencyResponse()

        mockMvc
            .perform(
                post("/api/v1/onboarding/competency-graph/competencies/$competencyId/reject")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("reason" to "not relevant"))),
            ).andExpect(status().isOk)
    }

    @Test
    fun `approveEdge should return 200 for a PM`() {
        every { competencyProposalService.approveEdge(edgeId) } returns edgeResponse()

        mockMvc
            .perform(post("/api/v1/onboarding/competency-graph/edges/$edgeId/approve").with(pmJwt))
            .andExpect(status().isOk)
    }

    @Test
    fun `approveEdge should return 403 for a plain USER`() {
        mockMvc
            .perform(post("/api/v1/onboarding/competency-graph/edges/$edgeId/approve").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `rejectEdge should return 200 for a PM`() {
        every { competencyProposalService.rejectEdge(edgeId, null) } returns edgeResponse()

        mockMvc
            .perform(post("/api/v1/onboarding/competency-graph/edges/$edgeId/reject").with(pmJwt))
            .andExpect(status().isOk)
    }

    @Test
    fun `approveBatch should return 200 for a PM`() {
        every { competencyProposalService.approveBatch(listOf(competencyId), listOf(edgeId)) } returns
            ApproveGraphBatchResponse(competenciesApproved = 1, edgesApproved = 1, graphVersion = 4)

        mockMvc
            .perform(
                post("/api/v1/onboarding/competency-graph/approve-batch")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "competencyProposalIds" to listOf(competencyId),
                                "edgeProposalIds" to listOf(edgeId),
                            ),
                        ),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.graphVersion").value(4))
    }
}
