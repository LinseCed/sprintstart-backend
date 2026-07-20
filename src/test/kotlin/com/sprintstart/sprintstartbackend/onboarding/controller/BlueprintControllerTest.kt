package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.ProposedBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.service.BlueprintService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@WebMvcTest(BlueprintController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class BlueprintControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var blueprintService: BlueprintService

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

    private fun blueprintResponse(): BlueprintResponse =
        BlueprintResponse(scope = "backend", version = "1", competencies = emptyList())

    private fun blueprintCompetencyResponse(id: UUID, status: ProposalStatus): BlueprintCompetencyResponse =
        BlueprintCompetencyResponse(
            competencyKey = "deploy-runbook",
            label = "Deploy the service",
            targetLevel = 2,
            targetLevelOverridden = false,
            requirement = "recommended",
            invariant = false,
            proposalId = id,
            status = status,
        )

    @Test
    fun `generateBlueprints should return 401 when not authenticated`() {
        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `listVersions should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/blueprints/backend/versions"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `rollback should return 401 when not authenticated`() {
        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/backend/rollback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `listProposed should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/blueprints/proposed"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `listProposed should return 200 for a PM`() {
        every { blueprintService.listProposed(null) } returns ProposedBlueprintsResponse(blueprints = emptyList())

        mockMvc
            .perform(get("/api/v1/onboarding/blueprints/proposed").with(pmJwt))
            .andExpect(status().isOk)
    }

    @Test
    fun `listProposed should return 403 for a plain USER`() {
        mockMvc
            .perform(get("/api/v1/onboarding/blueprints/proposed").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `approve should return 200 for a PM`() {
        every { blueprintService.approve("backend", "2") } returns blueprintResponse()

        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/backend/approve")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("version" to "2"))),
            ).andExpect(status().isOk)
    }

    @Test
    fun `approve should return 403 for a plain USER`() {
        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/backend/approve")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("version" to "2"))),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `reject should return 200 for a PM`() {
        every { blueprintService.reject("backend", "2", "obsolete") } returns blueprintResponse()

        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/backend/reject")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("version" to "2", "reason" to "obsolete"))),
            ).andExpect(status().isOk)
    }

    @Test
    fun `reject should return 403 for a plain USER`() {
        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/backend/reject")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("version" to "2"))),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `approveCompetency should return 200 for a PM`() {
        val id = UUID.randomUUID()
        every { blueprintService.approveCompetency(id) } returns
            blueprintCompetencyResponse(id, ProposalStatus.APPROVED)

        mockMvc
            .perform(post("/api/v1/onboarding/blueprints/competencies/$id/approve").with(pmJwt))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("APPROVED"))
    }

    @Test
    fun `approveCompetency should return 404 when the competency does not exist`() {
        val id = UUID.randomUUID()
        every { blueprintService.approveCompetency(id) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND, "No blueprint competency found with id: $id")

        mockMvc
            .perform(post("/api/v1/onboarding/blueprints/competencies/$id/approve").with(pmJwt))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `approveCompetency should return 403 for a plain USER`() {
        mockMvc
            .perform(post("/api/v1/onboarding/blueprints/competencies/${UUID.randomUUID()}/approve").with(userJwt))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `approveCompetency should return 401 when not authenticated`() {
        mockMvc
            .perform(post("/api/v1/onboarding/blueprints/competencies/${UUID.randomUUID()}/approve"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `rejectCompetency should return 200 for a PM`() {
        val id = UUID.randomUUID()
        every { blueprintService.rejectCompetency(id, "not expected of everyone") } returns
            blueprintCompetencyResponse(id, ProposalStatus.REJECTED)

        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/competencies/$id/reject")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mapOf("reason" to "not expected of everyone"))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REJECTED"))
    }

    @Test
    fun `rejectCompetency should return 409 when the competency is invariant`() {
        val id = UUID.randomUUID()
        every { blueprintService.rejectCompetency(id, null) } throws
            ResponseStatusException(HttpStatus.CONFLICT, "Cannot reject invariant baseline entry: $id")

        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/competencies/$id/reject")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `rejectCompetency should return 403 for a plain USER`() {
        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/competencies/${UUID.randomUUID()}/reject")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `rejectCompetency should return 401 when not authenticated`() {
        mockMvc
            .perform(
                post("/api/v1/onboarding/blueprints/competencies/${UUID.randomUUID()}/reject")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isUnauthorized)
    }
}
