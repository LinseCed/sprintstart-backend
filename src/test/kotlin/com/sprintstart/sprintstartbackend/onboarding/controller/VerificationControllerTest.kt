package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.model.request.verification.SubmitVerificationAttemptRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.verification.UpsertVerificationRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.verification.SubmitVerificationAttemptResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.verification.VerificationResponse
import com.sprintstart.sprintstartbackend.onboarding.service.VerificationService
import io.mockk.coEvery
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@WebMvcTest(VerificationController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class VerificationControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockkBean
    private lateinit var verificationService: VerificationService

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val objectMapper = jacksonObjectMapper()
    private val authId = "test-auth-id"
    private val stepId = UUID.randomUUID()

    private fun jwtWithRoles(vararg roles: String): JwtRequestPostProcessor =
        jwt()
            .jwt { jwt ->
                jwt.subject(authId)
                jwt.claim("realm_access", mapOf("roles" to roles.toList()))
            }.authorities(roles.map { SimpleGrantedAuthority("ROLE_$it") })

    private val userJwt = jwtWithRoles("USER")
    private val pmJwt = jwtWithRoles("PM")

    @Test
    fun `getVerificationForMe should return 200 with the verification`() {
        every { verificationService.getVerificationForMe(authId, stepId) } returns
            VerificationResponse(
                id = UUID.randomUUID(),
                stepId = stepId,
                type = VerificationType.KNOWLEDGE,
                prompt = "Why?",
                competencyKey = "kotlin",
                level = "beginner",
            )

        mockMvc
            .perform(get("/api/v1/onboarding/me/steps/$stepId/verification").with(userJwt))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.competencyKey").value("kotlin"))
    }

    @Test
    fun `getVerificationForMe should return 401 when not authenticated`() {
        mockMvc
            .perform(get("/api/v1/onboarding/me/steps/$stepId/verification"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `submitVerificationAttemptForMe should return 201 with the graded result`() {
        every {
            verificationService.submitAttemptForMe(authId, stepId, SubmitVerificationAttemptRequest("chroma"))
        } returns SubmitVerificationAttemptResponse(
            attemptId = UUID.randomUUID(),
            stepId = stepId,
            passed = true,
            score = 1.0,
            feedback = "Matches exactly.",
            attemptNo = 1,
            graphVersion = 1,
            stepStatus = StepStatus.FINISHED,
        )

        mockMvc
            .perform(
                post("/api/v1/onboarding/me/steps/$stepId/verification/attempts")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SubmitVerificationAttemptRequest("chroma"))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.passed").value(true))
            .andExpect(jsonPath("$.stepStatus").value("FINISHED"))
    }

    @Test
    fun `submitVerificationAttemptForMe should return 400 when the step is already finished`() {
        every {
            verificationService.submitAttemptForMe(authId, stepId, SubmitVerificationAttemptRequest("x"))
        } throws ResponseStatusException(HttpStatus.BAD_REQUEST, "already finished")

        mockMvc
            .perform(
                post("/api/v1/onboarding/me/steps/$stepId/verification/attempts")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SubmitVerificationAttemptRequest("x"))),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `upsertVerification should return 200 for a PM`() {
        val request = UpsertVerificationRequest(
            type = VerificationType.EXACT,
            prompt = "What DB?",
            canonicalAnswer = "Chroma",
            competencyKey = "kotlin",
            level = "beginner",
        )
        every { verificationService.upsertVerification(stepId, request) } returns
            VerificationResponse(
                id = UUID.randomUUID(),
                stepId = stepId,
                type = VerificationType.EXACT,
                prompt = "What DB?",
                competencyKey = "kotlin",
                level = "beginner",
            )

        mockMvc
            .perform(
                put("/api/v1/onboarding/steps/$stepId/verification")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("EXACT"))
    }

    @Test
    fun `upsertVerification should return 200 for ARTIFACT with a repositoryConnectionId`() {
        val repositoryConnectionId = UUID.randomUUID()
        val request = UpsertVerificationRequest(
            type = VerificationType.ARTIFACT,
            prompt = "Ship it",
            rubric = "closes the ticket",
            repositoryConnectionId = repositoryConnectionId,
            competencyKey = "kotlin",
            level = "beginner",
        )
        every { verificationService.upsertVerification(stepId, request) } returns
            VerificationResponse(
                id = UUID.randomUUID(),
                stepId = stepId,
                type = VerificationType.ARTIFACT,
                prompt = "Ship it",
                competencyKey = "kotlin",
                level = "beginner",
            )

        mockMvc
            .perform(
                put("/api/v1/onboarding/steps/$stepId/verification")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("ARTIFACT"))
    }

    @Test
    fun `upsertVerification should return 400 when ARTIFACT has no repositoryConnectionId`() {
        val request = UpsertVerificationRequest(
            type = VerificationType.ARTIFACT,
            prompt = "Ship it",
            rubric = "closes the ticket",
            competencyKey = "kotlin",
            level = "beginner",
        )
        every { verificationService.upsertVerification(stepId, request) } throws
            ResponseStatusException(HttpStatus.BAD_REQUEST, "ARTIFACT verifications need a repositoryConnectionId")

        mockMvc
            .perform(
                put("/api/v1/onboarding/steps/$stepId/verification")
                    .with(pmJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `upsertVerification should return 403 for a plain user`() {
        val request = UpsertVerificationRequest(
            type = VerificationType.ATTEST,
            prompt = "Confirm?",
            competencyKey = "kotlin",
            level = "beginner",
        )

        mockMvc
            .perform(
                put("/api/v1/onboarding/steps/$stepId/verification")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `synthesizeContent should return 204`() {
        coEvery { verificationService.synthesizeContent(stepId) } returns Unit

        val mvcResult = mockMvc
            .perform(post("/api/v1/onboarding/steps/$stepId/verification/synthesize-content").with(pmJwt))
            .andExpect(request().asyncStarted())
            .andReturn()

        mockMvc
            .perform(asyncDispatch(mvcResult))
            .andExpect(status().isNoContent)
    }
}
