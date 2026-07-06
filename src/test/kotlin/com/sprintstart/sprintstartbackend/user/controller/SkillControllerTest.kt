package com.sprintstart.sprintstartbackend.user.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.config.SecurityConfig
import com.sprintstart.sprintstartbackend.user.external.enums.SkillLevel
import com.sprintstart.sprintstartbackend.user.external.enums.SkillStatus
import com.sprintstart.sprintstartbackend.user.model.request.skill.CreateSkillAssessmentRequest
import com.sprintstart.sprintstartbackend.user.model.request.skill.CreateSkillRequest
import com.sprintstart.sprintstartbackend.user.model.request.skill.UpdateSkillRequest
import com.sprintstart.sprintstartbackend.user.model.response.skill.SkillAssessmentDto
import com.sprintstart.sprintstartbackend.user.model.response.skill.SkillDto
import com.sprintstart.sprintstartbackend.user.service.SkillService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@WebMvcTest(SkillController::class)
@Import(SecurityConfig::class)
@AutoConfigureMockMvc
class SkillControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private val objectMapper = jacksonObjectMapper()

    @MockkBean
    private lateinit var skillService: SkillService

    @MockkBean
    private lateinit var jwtDecoder: JwtDecoder

    private val userJwt = jwt().authorities(SimpleGrantedAuthority("ROLE_USER"))
    private val adminJwt = jwt().authorities(
        SimpleGrantedAuthority("ROLE_USER"),
        SimpleGrantedAuthority("ROLE_ADMIN"),
    )

    private fun skillDto(
        id: UUID = UUID.randomUUID(),
        name: String = "Kotlin",
        status: SkillStatus = SkillStatus.ACTIVE,
    ) = SkillDto(id = id, name = name, roleId = UUID.randomUUID(), description = null, status = status)

    @Test
    fun `getAllSkills returns 200 with skill list including status`() {
        val dto = skillDto()
        every { skillService.getAllSkills() } returns listOf(dto)

        mockMvc
            .perform(get("/api/v1/skills").with(userJwt))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { skillService.getAllSkills() }
    }

    @Test
    fun `getSkillById returns 200`() {
        val dto = skillDto()
        every { skillService.getSkillById(dto.id) } returns dto

        mockMvc
            .perform(get("/api/v1/skills/${dto.id}").with(userJwt))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { skillService.getSkillById(dto.id) }
    }

    @Test
    fun `getSkillById returns 404 when not found`() {
        val id = UUID.randomUUID()
        every { skillService.getSkillById(id) } throws ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc
            .perform(get("/api/v1/skills/$id").with(userJwt))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `createSkill returns 201 for admins`() {
        val request = CreateSkillRequest("Kotlin", UUID.randomUUID())
        val dto = skillDto(name = "Kotlin")
        every { skillService.createSkill(request) } returns dto

        mockMvc
            .perform(
                post("/api/v1/skills")
                    .with(adminJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isCreated)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { skillService.createSkill(request) }
    }

    @Test
    fun `createSkill returns 403 for normal users`() {
        val request = CreateSkillRequest("Kotlin", UUID.randomUUID())

        mockMvc
            .perform(
                post("/api/v1/skills")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isForbidden)

        verify(exactly = 0) { skillService.createSkill(any()) }
    }

    @Test
    fun `updateSkill returns 200 for admins`() {
        val id = UUID.randomUUID()
        val request = UpdateSkillRequest(name = "Go", description = "A language", roleId = null)
        val dto = skillDto(id = id, name = "Go")
        every { skillService.updateSkill(id, request) } returns dto

        mockMvc
            .perform(
                patch("/api/v1/skills/$id")
                    .with(adminJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { skillService.updateSkill(id, request) }
    }

    @Test
    fun `retireSkill returns 204`() {
        val skillId = UUID.randomUUID()
        every { skillService.retireSkill(skillId) } just Runs

        mockMvc
            .perform(delete("/api/v1/skills/$skillId").with(adminJwt))
            .andExpect(status().isNoContent)

        verify(exactly = 1) { skillService.retireSkill(skillId) }
    }

    @Test
    fun `retireSkill returns 404 when skill not found`() {
        val skillId = UUID.randomUUID()
        every { skillService.retireSkill(skillId) } throws ResponseStatusException(HttpStatus.NOT_FOUND)

        mockMvc
            .perform(delete("/api/v1/skills/$skillId").with(adminJwt))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `getUserSkillAssessments returns 200`() {
        val userId = UUID.randomUUID()
        val assessment = SkillAssessmentDto(userId = userId, skillId = UUID.randomUUID(), level = SkillLevel.BEGINNER)
        every { skillService.getUserSkillAssessments(userId) } returns listOf(assessment)

        mockMvc
            .perform(get("/api/v1/users/$userId/skill-assessments/completed").with(userJwt))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { skillService.getUserSkillAssessments(userId) }
    }

    @Test
    fun `assessSkill returns 200`() {
        val request = CreateSkillAssessmentRequest(skillId = UUID.randomUUID(), level = SkillLevel.EXPERT)
        val assessment = SkillAssessmentDto(
            userId = UUID.randomUUID(),
            skillId = request.skillId,
            level = request.level,
        )
        every { skillService.assessSkillForMe(any(), request) } returns assessment

        mockMvc
            .perform(
                post("/api/v1/skill-assessments")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))

        verify(exactly = 1) { skillService.assessSkillForMe(any(), request) }
    }

    @Test
    fun `assessSkill returns 400 when skill is retired`() {
        val request = CreateSkillAssessmentRequest(skillId = UUID.randomUUID(), level = SkillLevel.BEGINNER)
        every { skillService.assessSkillForMe(any(), request) } throws
            ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill is retired")

        mockMvc
            .perform(
                post("/api/v1/skill-assessments")
                    .with(userJwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isBadRequest)
    }
}
