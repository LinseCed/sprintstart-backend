package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BuddyMessageRole
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyStreamEvent
import com.sprintstart.sprintstartbackend.onboarding.model.request.buddy.SendBuddyMessageRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.buddy.BuddyMessageResponse
import com.sprintstart.sprintstartbackend.onboarding.service.BuddyService
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.Instant

@WebMvcTest(BuddyController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class BuddyControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var buddyService: BuddyService

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val objectMapper = jacksonObjectMapper()
    private val authId = "test-auth-id"

    private fun jwtWithRoles(vararg roles: String): JwtRequestPostProcessor =
        jwt()
            .jwt { jwt ->
                jwt.subject(authId)
                jwt.claim("realm_access", mapOf("roles" to roles.toList()))
            }.authorities(roles.map { SimpleGrantedAuthority("ROLE_$it") })

    private val userJwt = jwtWithRoles("USER")
    private val noUserRoleJwt = jwtWithRoles("PM")

    @Test
    fun `getMessagesForMe should return 200 with the conversation`() {
        every { buddyService.getMessagesForMe(authId) } returns listOf(
            BuddyMessageResponse(role = BuddyMessageRole.USER, content = "Hi", createdAt = Instant.now()),
        )

        mockMvc
            .perform(get("/api/v1/onboarding/me/buddy/messages").with(userJwt))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].content").value("Hi"))
    }

    @Test
    fun `getMessagesForMe should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/me/buddy/messages"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `sendMessageForMe should stream tokens and done`() {
        val events = listOf(
            BuddyStreamEvent(type = "token", content = "No question "),
            BuddyStreamEvent(type = "token", content = "is too basic."),
            BuddyStreamEvent(type = "done"),
        )
        coEvery { buddyService.sendMessageForMe(authId, "How do I get set up?") } returns flowOf(*events.toTypedArray())

        val asyncResult = mockMvc
            .perform(
                post("/api/v1/onboarding/me/buddy/messages")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SendBuddyMessageRequest("How do I get set up?"))),
            ).andExpect(request().asyncStarted())
            .andReturn()

        val mvcResult = mockMvc
            .perform(asyncDispatch(asyncResult))
            .andExpect(status().isOk)
            .andReturn()

        val actual = mvcResult.response.contentAsString
            .replace("data:", "")
            .replace("\n", "")
        val expected = events.joinToString("") { Json.encodeToString(it) }

        assertEquals(expected, actual)
    }

    @Test
    fun `sendMessageForMe should return 403 for a non-USER role`() {
        val asyncResult = mockMvc
            .perform(
                post("/api/v1/onboarding/me/buddy/messages")
                    .with(noUserRoleJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SendBuddyMessageRequest("Hi"))),
            ).andExpect(request().asyncStarted())
            .andReturn()

        mockMvc
            .perform(asyncDispatch(asyncResult))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `sendMessageForMe should return 401 when not authenticated`() {
        mockMvc
            .perform(
                post("/api/v1/onboarding/me/buddy/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SendBuddyMessageRequest("Hi"))),
            ).andExpect(status().isUnauthorized)
    }
}
