package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.ProposedBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.service.BlueprintService
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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
        BlueprintResponse(scope = "backend", version = "1", steps = emptyList())

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
}
