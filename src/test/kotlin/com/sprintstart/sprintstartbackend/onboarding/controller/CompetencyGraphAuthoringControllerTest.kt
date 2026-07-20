package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyEdgeResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.DeleteCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphAuthoringService
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException

@WebMvcTest(CompetencyGraphAuthoringController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class CompetencyGraphAuthoringControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
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
    private val hrJwt = jwtWithRoles("HR")
    private val userJwt = jwtWithRoles("USER")

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

    @Test
    fun `getGraph should return the whole graph for a PM`() {
        every { competencyGraphAuthoringService.getGraph() } returns CompetencyGraphResponse(
            competencies = listOf(liveCompetencyResponse()),
            edges = listOf(CompetencyEdgeResponse("kotlin", "spring", EdgeKind.PREREQUISITE)),
            graphVersion = 7,
        )

        mockMvc
            .perform(get("/api/v1/onboarding/competency-graph").with(pmJwt))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.competencies[0].key").value("kotlin"))
            .andExpect(jsonPath("$.edges[0].toKey").value("spring"))
            .andExpect(jsonPath("$.graphVersion").value(7))
    }

    @Test
    fun `getGraph should be readable by HR, which reviews the graph without authoring it`() {
        every { competencyGraphAuthoringService.getGraph() } returns CompetencyGraphResponse(
            competencies = emptyList(),
            edges = emptyList(),
            graphVersion = 1,
        )

        mockMvc
            .perform(get("/api/v1/onboarding/competency-graph").with(hrJwt))
            .andExpect(status().isOk)
    }

    @Test
    fun `getGraph should return 403 for a plain USER`() {
        mockMvc
            .perform(get("/api/v1/onboarding/competency-graph").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `updateCompetency should return 200 for a PM`() {
        every { competencyGraphAuthoringService.updateCompetency("kotlin", any()) } returns liveCompetencyResponse()

        mockMvc
            .perform(
                put("/api/v1/onboarding/competency-graph/competencies/kotlin")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("label" to "Kotlin Basics", "targetLevel" to 3))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.key").value("kotlin"))
    }

    @Test
    fun `updateCompetency should return 403 for a plain USER`() {
        mockMvc
            .perform(
                put("/api/v1/onboarding/competency-graph/competencies/kotlin")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("label" to "Kotlin Basics"))),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `updateCompetency should return 403 for HR, which reviews but does not author`() {
        mockMvc
            .perform(
                put("/api/v1/onboarding/competency-graph/competencies/kotlin")
                    .with(jwtWithRoles("HR"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("label" to "Kotlin Basics"))),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `deleteCompetency should return 200 for a PM`() {
        every { competencyGraphAuthoringService.deleteCompetency("kotlin") } returns
            DeleteCompetencyResponse(key = "kotlin", edgesRemoved = 2, graphVersion = 5)

        mockMvc
            .perform(delete("/api/v1/onboarding/competency-graph/competencies/kotlin").with(pmJwt))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.edgesRemoved").value(2))
    }

    @Test
    fun `deleteCompetency should return 403 for a plain USER`() {
        mockMvc
            .perform(delete("/api/v1/onboarding/competency-graph/competencies/kotlin").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `createEdge should return 200 for a PM`() {
        every { competencyGraphAuthoringService.createEdge(any()) } returns
            CompetencyEdgeResponse("kotlin", "spring", EdgeKind.PREREQUISITE)

        mockMvc
            .perform(
                post("/api/v1/onboarding/competency-graph/edges")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("fromKey" to "kotlin", "toKey" to "spring"))),
            ).andExpect(status().isOk)
    }

    @Test
    fun `createEdge should surface a rejected cycle as a 400`() {
        every { competencyGraphAuthoringService.createEdge(any()) } throws
            ResponseStatusException(HttpStatus.BAD_REQUEST, "would create a cycle")

        mockMvc
            .perform(
                post("/api/v1/onboarding/competency-graph/edges")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("fromKey" to "spring", "toKey" to "kotlin"))),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `deleteEdge should return 200 for a PM`() {
        every {
            competencyGraphAuthoringService.deleteEdge("kotlin", "spring", EdgeKind.PREREQUISITE)
        } returns CompetencyEdgeResponse("kotlin", "spring", EdgeKind.PREREQUISITE)

        mockMvc
            .perform(
                delete("/api/v1/onboarding/competency-graph/edges")
                    .with(pmJwt)
                    .param("fromKey", "kotlin")
                    .param("toKey", "spring"),
            ).andExpect(status().isOk)
    }

    @Test
    fun `deleteEdge should return 403 for a plain USER`() {
        mockMvc
            .perform(
                delete("/api/v1/onboarding/competency-graph/edges")
                    .with(userJwt)
                    .param("fromKey", "kotlin")
                    .param("toKey", "spring"),
            ).andExpect(status().isForbidden)
    }
}
