package com.sprintstart.sprintstartbackend.onboarding.controller

import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathResponse
import com.sprintstart.sprintstartbackend.onboarding.service.OnboardingPathService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@WebMvcTest(OnboardingPathController::class)
@AutoConfigureMockMvc
class OnboardingPathControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var onboardingPathService: OnboardingPathService

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val authId = "test-auth-id"
    private val userId = UUID.randomUUID()
    private val pathId = UUID.randomUUID()

    // ========================== /me endpoints ==========================

    @Test
    fun `getPathForMe should return 200 and path`() {
        val response = GetOnboardingPathForUserResponse(
            id = pathId,
            userId = userId,
            createdAt = Instant.now(),
            phases = emptyList(),
        )

        every { onboardingPathService.getOnboardingPathByAuthId(authId) } returns response

        mockMvc
            .perform(
                get("/api/v1/onboarding/me/path")
                    .with(jwt().jwt { it.subject(authId) }.authorities(SimpleGrantedAuthority("ROLE_USER"))),
            ).andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { onboardingPathService.getOnboardingPathByAuthId(authId) }
    }

    @Test
    fun `getPathForMe should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/me/path"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `getPathForMe should return 403 when authenticated with wrong role`() {
        mockMvc
            .perform(
                get("/api/v1/onboarding/me/path")
                    .with(jwt().authorities(SimpleGrantedAuthority("ROLE_ADMIN"))),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `getPathForMe should return 404 when not found`() {
        every { onboardingPathService.getOnboardingPathByAuthId(authId) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc
            .perform(
                get("/api/v1/onboarding/me/path")
                    .with(jwt().jwt { it.subject(authId) }.authorities(SimpleGrantedAuthority("ROLE_USER"))),
            ).andExpect(status().isNotFound)

        verify(exactly = 1) { onboardingPathService.getOnboardingPathByAuthId(authId) }
    }

    @Test
    fun `deletePathForMe should return 204`() {
        every { onboardingPathService.deleteOnboardingPathByAuthId(authId) } just Runs

        mockMvc
            .perform(
                delete("/api/v1/onboarding/me/path")
                    .with(jwt().jwt { it.subject(authId) }.authorities(SimpleGrantedAuthority("ROLE_USER"))),
            ).andExpect(status().isNoContent)

        verify(exactly = 1) { onboardingPathService.deleteOnboardingPathByAuthId(authId) }
    }

    @Test
    fun `deletePathForMe should return 401 when not authenticated`() {
        mockMvc
            .perform(delete("/api/v1/onboarding/me/path"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `deletePathForMe should return 403 when authenticated with wrong role`() {
        mockMvc
            .perform(
                delete("/api/v1/onboarding/me/path")
                    .with(jwt().authorities(SimpleGrantedAuthority("ROLE_ADMIN"))),
            ).andExpect(status().isForbidden)
    }

    // ========================== Admin endpoints ==========================

    @Test
    fun `getPath should return 200 and path overview`() {
        val response = GetOnboardingPathResponse(
            id = pathId,
            userId = userId,
            createdAt = Instant.now(),
            phases = emptyList(),
        )

        every { onboardingPathService.getOnboardingPathOverviewByUserId(userId) } returns response

        mockMvc
            .perform(
                get("/api/v1/onboarding/users/$userId/path")
                    .with(jwt().authorities(SimpleGrantedAuthority("ROLE_ADMIN"))),
            ).andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { onboardingPathService.getOnboardingPathOverviewByUserId(userId) }
    }

    @Test
    fun `getPath should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/users/$userId/path"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `getPath should return 403 when authenticated with wrong role`() {
        mockMvc
            .perform(
                get("/api/v1/onboarding/users/$userId/path")
                    .with(jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `getPath should return 404 when not found`() {
        every { onboardingPathService.getOnboardingPathOverviewByUserId(userId) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc
            .perform(
                get("/api/v1/onboarding/users/$userId/path")
                    .with(jwt().authorities(SimpleGrantedAuthority("ROLE_ADMIN"))),
            ).andExpect(status().isNotFound)

        verify(exactly = 1) { onboardingPathService.getOnboardingPathOverviewByUserId(userId) }
    }

    @Test
    fun `deletePathByUserId should return 204`() {
        every { onboardingPathService.deleteOnboardingPathByUserId(userId) } just Runs

        mockMvc
            .perform(
                delete("/api/v1/onboarding/users/$userId/path")
                    .with(jwt().authorities(SimpleGrantedAuthority("ROLE_ADMIN"))),
            ).andExpect(status().isNoContent)

        verify(exactly = 1) { onboardingPathService.deleteOnboardingPathByUserId(userId) }
    }

    @Test
    fun `deletePathByUserId should return 401 when not authenticated`() {
        mockMvc
            .perform(delete("/api/v1/onboarding/users/$userId/path"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `deletePathByUserId should return 403 when authenticated with wrong role`() {
        mockMvc
            .perform(
                delete("/api/v1/onboarding/users/$userId/path")
                    .with(jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `deletePathByUserId should return 404 when not found`() {
        every { onboardingPathService.deleteOnboardingPathByUserId(userId) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc
            .perform(
                delete("/api/v1/onboarding/users/$userId/path")
                    .with(jwt().authorities(SimpleGrantedAuthority("ROLE_ADMIN"))),
            ).andExpect(status().isNotFound)

        verify(exactly = 1) { onboardingPathService.deleteOnboardingPathByUserId(userId) }
    }
}
