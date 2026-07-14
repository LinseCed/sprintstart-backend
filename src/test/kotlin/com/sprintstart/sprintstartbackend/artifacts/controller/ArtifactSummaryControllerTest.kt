package com.sprintstart.sprintstartbackend.artifacts.controller

import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.artifacts.model.dto.response.ArtifactSummaryCitationResponse
import com.sprintstart.sprintstartbackend.artifacts.model.dto.response.ArtifactSummaryResponse
import com.sprintstart.sprintstartbackend.artifacts.service.ArtifactSummaryService
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@WebMvcTest(ArtifactSummaryController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class ArtifactSummaryControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var artifactSummaryService: ArtifactSummaryService

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val artifactId = UUID.randomUUID()

    private fun jwtWithRoles(vararg roles: String): JwtRequestPostProcessor {
        return jwt()
            .jwt { jwt ->
                jwt.subject("test-auth-id")
                jwt.claim("realm_access", mapOf("roles" to roles.toList()))
            }.authorities(roles.map { role -> SimpleGrantedAuthority("ROLE_$role") })
    }

    private val userJwt = jwtWithRoles("USER")

    private fun buildSummary() = ArtifactSummaryResponse(
        artifactId = artifactId,
        summary = "A short summary of the artifact.",
        citations = listOf(
            ArtifactSummaryCitationResponse(
                artifactId = UUID.randomUUID(),
                filename = "README.md",
                sourceUrl = "https://github.com/example/repo",
            ),
        ),
        generatedAt = Instant.parse("2026-07-01T00:00:00Z"),
    )

    @Test
    fun `getSummary should return 200 and the summary for an authenticated user`() {
        coEvery { artifactSummaryService.getSummary(artifactId) } returns buildSummary()

        val asyncResult = mockMvc
            .perform(get("/api/v1/artifacts/$artifactId/summary").with(userJwt))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc
            .perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        coVerify(exactly = 1) { artifactSummaryService.getSummary(artifactId) }
    }

    @Test
    fun `getSummary should return 404 when the artifact does not exist`() {
        coEvery { artifactSummaryService.getSummary(artifactId) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact $artifactId not found")

        val asyncResult = mockMvc
            .perform(get("/api/v1/artifacts/$artifactId/summary").with(userJwt))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc
            .perform(asyncDispatch(asyncResult))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `getSummary should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/artifacts/$artifactId/summary"))
            .andExpect(status().isUnauthorized)
    }
}
