package com.sprintstart.sprintstartbackend.onboarding.controller

import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.OrientationStep
import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.MyOrientationResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.OrientationCitationResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.OrientationPacketResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.OrientationSectionResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.OrientationSourceResponse
import com.sprintstart.sprintstartbackend.onboarding.service.TaskOrientationService
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.coEvery
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.Optional
import java.util.UUID

@WebMvcTest(TaskOrientationController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class TaskOrientationControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var taskOrientationService: TaskOrientationService

    @MockkBean
    private lateinit var userApi: UserApi

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val authId = "test-auth-id"
    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()

    private fun jwtWithSubject(subject: String, vararg roles: String): JwtRequestPostProcessor =
        jwt()
            .jwt { jwt ->
                jwt.subject(subject)
                jwt.claim("realm_access", mapOf("roles" to roles.toList()))
            }.authorities(roles.map { SimpleGrantedAuthority("ROLE_$it") })

    private val userJwt = jwtWithSubject(authId, "USER")

    private fun packet() = OrientationPacketResponse(
        taskId = taskId,
        taskTitle = "Fix the stale cache header",
        summary = "What you need to change the header.",
        sections = listOf(
            OrientationSectionResponse(
                step = OrientationStep.SET_UP,
                title = "Run it locally",
                body = "Run `make dev`.",
                citations = listOf(
                    OrientationCitationResponse("README.md", "c1", "https://example.test/README.md"),
                ),
            ),
        ),
        sources = listOf(OrientationSourceResponse("README.md", "https://example.test/README.md", "FILE")),
        assembledAt = Instant.parse("2026-07-21T12:00:00Z"),
    )

    // A suspend handler is dispatched asynchronously, so the real (post-authorization) status is
    // only observable through asyncStarted() -> asyncDispatch(). A single-step andExpect here
    // silently passes whatever the first dispatch produced.
    private fun performGet(jwtProcessor: JwtRequestPostProcessor) =
        mockMvc
            .perform(
                get("/api/v1/onboarding/me/orientation")
                    .param("projectId", projectId.toString())
                    .with(jwtProcessor),
            ).andExpect(request().asyncStarted())
            .andReturn()
            .let { mockMvc.perform(asyncDispatch(it)) }

    @Test
    fun `returns the packet with its provenance intact`() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        coEvery { taskOrientationService.getForHire(userId, projectId) } returns
            MyOrientationResponse(
                taskId = taskId,
                taskTitle = "Fix the stale cache header",
                taskUrl = "https://github.com/org/repo/issues/7",
                packet = packet(),
                reason = null,
            )

        performGet(userJwt)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.packet.sections[0].step").value("SET_UP"))
            .andExpect(jsonPath("$.packet.sections[0].citations[0].sourceUrl").value("https://example.test/README.md"))
            .andExpect(jsonPath("$.packet.sources[0].filename").value("README.md"))
    }

    @Test
    fun `a packet that could not be assembled is a 200 with the reason, not an error`() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        coEvery { taskOrientationService.getForHire(userId, projectId) } returns
            MyOrientationResponse(
                taskId = taskId,
                taskTitle = "Fix the stale cache header",
                taskUrl = "https://github.com/org/repo/issues/7",
                packet = null,
                reason = "corpus is empty",
            )

        performGet(userJwt)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.packet").doesNotExist())
            .andExpect(jsonPath("$.reason").value("corpus is empty"))
            .andExpect(jsonPath("$.taskUrl").value("https://github.com/org/repo/issues/7"))
    }

    @Test
    fun `requires authentication`() {
        mockMvc
            .perform(get("/api/v1/onboarding/me/orientation").param("projectId", projectId.toString()))
            .andExpect(status().isUnauthorized)
    }
}
