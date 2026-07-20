package com.sprintstart.sprintstartbackend.onboarding.controller

import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.model.entity.EnvironmentEvidence
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.MyEnvironmentResponse
import com.sprintstart.sprintstartbackend.onboarding.service.EnvironmentReadinessService
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.every
import io.mockk.verify
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
import java.time.Instant
import java.util.Optional
import java.util.UUID

@WebMvcTest(EnvironmentReadinessController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class EnvironmentReadinessControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var environmentReadinessService: EnvironmentReadinessService

    @MockkBean
    private lateinit var userApi: UserApi

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val authId = "test-auth-id"
    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    private fun jwtWithSubject(subject: String, vararg roles: String): JwtRequestPostProcessor =
        jwt()
            .jwt { jwt ->
                jwt.subject(subject)
                jwt.claim("realm_access", mapOf("roles" to roles.toList()))
            }.authorities(roles.map { SimpleGrantedAuthority("ROLE_$it") })

    private val userJwt = jwtWithSubject(authId, "USER")
    private val noRoleJwt = jwtWithSubject(authId, "NONE")

    @Test
    fun `getMyEnvironment returns the caller's readiness`() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        every { environmentReadinessService.getReadiness(userId, projectId) } returns
            MyEnvironmentResponse(
                ready = true,
                readyAt = Instant.parse("2026-07-19T00:00:00Z"),
                evidence = EnvironmentEvidence.PULL_REQUEST,
                evidenceDetail = "A pull request you authored",
                derived = true,
            )

        mockMvc
            .perform(get("/api/v1/onboarding/me/environment").param("projectId", projectId.toString()).with(userJwt))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ready").value(true))
            .andExpect(jsonPath("$.evidence").value("PULL_REQUEST"))
            .andExpect(jsonPath("$.derived").value(true))
    }

    @Test
    fun `report records readiness and returns 201`() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        every {
            environmentReadinessService.report(userId, projectId, EnvironmentEvidence.BUILD_TEST_RUN, null, "ok")
        } returns MyEnvironmentResponse(
            ready = true,
            readyAt = Instant.parse("2026-07-20T00:00:00Z"),
            evidence = EnvironmentEvidence.BUILD_TEST_RUN,
            evidenceDetail = "ok",
            derived = false,
        )

        mockMvc
            .perform(
                post("/api/v1/onboarding/me/environment/report")
                    .param("projectId", projectId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"evidence":"BUILD_TEST_RUN","detail":"ok"}""")
                    .with(userJwt),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.evidence").value("BUILD_TEST_RUN"))

        verify(exactly = 1) {
            environmentReadinessService.report(userId, projectId, EnvironmentEvidence.BUILD_TEST_RUN, null, "ok")
        }
    }

    @Test
    fun `getMyEnvironment requires authentication`() {
        mockMvc
            .perform(get("/api/v1/onboarding/me/environment").param("projectId", projectId.toString()))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `getMyEnvironment forbids an unknown role`() {
        mockMvc
            .perform(get("/api/v1/onboarding/me/environment").param("projectId", projectId.toString()).with(noRoleJwt))
            .andExpect(status().isForbidden)
    }
}
