package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.user.external.enums.SkillLevel
import com.sprintstart.sprintstartbackend.user.external.enums.SkillStatus
import com.sprintstart.sprintstartbackend.user.model.entity.ProjectRole
import com.sprintstart.sprintstartbackend.user.model.entity.Skill
import com.sprintstart.sprintstartbackend.user.model.entity.User
import com.sprintstart.sprintstartbackend.user.model.entity.UserSkillAssessment
import com.sprintstart.sprintstartbackend.user.model.request.skill.CreateSkillAssessmentRequest
import com.sprintstart.sprintstartbackend.user.model.request.skill.CreateSkillRequest
import com.sprintstart.sprintstartbackend.user.model.request.skill.UpdateSkillRequest
import com.sprintstart.sprintstartbackend.user.repository.ProjectRoleRepository
import com.sprintstart.sprintstartbackend.user.repository.SkillRepository
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import com.sprintstart.sprintstartbackend.user.repository.UserSkillAssessmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

class SkillServiceTest {
    private val skillRepository: SkillRepository = mockk()
    private val projectRoleRepository: ProjectRoleRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val userSkillAssessmentRepository: UserSkillAssessmentRepository = mockk()
    private val service =
        SkillService(skillRepository, projectRoleRepository, userRepository, userSkillAssessmentRepository)

    private fun role(id: UUID = UUID.randomUUID()) = ProjectRole(id = id, name = "Dev", description = "Test")

    private fun skill(
        id: UUID = UUID.randomUUID(),
        name: String = "Kotlin",
        status: SkillStatus = SkillStatus.ACTIVE,
        role: ProjectRole = role(),
    ) = Skill(id = id, name = name, projectRole = role, status = status)

    @Test
    fun `getAllSkills returns list of mapped skills`() {
        val r = role()
        val s = skill(role = r)
        every { skillRepository.findAll() } returns listOf(s)

        val result = service.getAllSkills()

        assertEquals(1, result.size)
        assertEquals("Kotlin", result[0].name)
        assertEquals(r.id, result[0].roleId)
        assertEquals(SkillStatus.ACTIVE, result[0].status)
    }

    @Test
    fun `getSkillById returns skill when found`() {
        val s = skill()
        every { skillRepository.findById(s.id) } returns Optional.of(s)

        val result = service.getSkillById(s.id)

        assertEquals(s.id, result.id)
        assertEquals("Kotlin", result.name)
    }

    @Test
    fun `getSkillById throws 404 when not found`() {
        val id = UUID.randomUUID()
        every { skillRepository.findById(id) } returns Optional.empty()

        val ex = assertThrows<ResponseStatusException> { service.getSkillById(id) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `createSkill saves and returns skill`() {
        val roleId = UUID.randomUUID()
        val r = role(roleId)
        val request = CreateSkillRequest(name = "Kotlin", roleId = roleId)

        every { skillRepository.existsByNormalizedName("Kotlin") } returns false
        every { projectRoleRepository.findById(roleId) } returns Optional.of(r)
        every { skillRepository.save(any()) } answers { firstArg() }

        val result = service.createSkill(request)

        assertEquals("Kotlin", result.name)
        assertEquals(roleId, result.roleId)
        assertEquals(SkillStatus.ACTIVE, result.status)
        verify(exactly = 1) { skillRepository.save(any()) }
    }

    @Test
    fun `createSkill throws 409 if normalized name already exists`() {
        val roleId = UUID.randomUUID()
        val request = CreateSkillRequest(name = "kotlin", roleId = roleId)

        every { skillRepository.existsByNormalizedName("kotlin") } returns true

        val ex = assertThrows<ResponseStatusException> { service.createSkill(request) }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `createSkill throws 404 if role not found`() {
        val roleId = UUID.randomUUID()
        val request = CreateSkillRequest(name = "Kotlin", roleId = roleId)

        every { skillRepository.existsByNormalizedName("Kotlin") } returns false
        every { projectRoleRepository.findById(roleId) } returns Optional.empty()

        val ex = assertThrows<ResponseStatusException> { service.createSkill(request) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `updateSkill changes editable fields`() {
        val s = skill()
        val newRoleId = UUID.randomUUID()
        val newRole = role(newRoleId)
        val request = UpdateSkillRequest(name = "Go", description = "New desc", roleId = newRoleId)

        every { skillRepository.findById(s.id) } returns Optional.of(s)
        every { skillRepository.existsByNormalizedNameExcluding("Go", s.id) } returns false
        every { projectRoleRepository.findById(newRoleId) } returns Optional.of(newRole)
        every { skillRepository.save(any()) } answers { firstArg() }

        val result = service.updateSkill(s.id, request)

        assertEquals("Go", result.name)
        assertEquals("New desc", result.description)
        assertEquals(newRoleId, result.roleId)
    }

    @Test
    fun `updateSkill throws 409 if name conflicts with another skill`() {
        val s = skill()
        val request = UpdateSkillRequest(name = "Go", description = null, roleId = null)

        every { skillRepository.findById(s.id) } returns Optional.of(s)
        every { skillRepository.existsByNormalizedNameExcluding("Go", s.id) } returns true

        val ex = assertThrows<ResponseStatusException> { service.updateSkill(s.id, request) }
        assertEquals(HttpStatus.CONFLICT, ex.statusCode)
    }

    @Test
    fun `updateSkill throws 404 when skill not found`() {
        val id = UUID.randomUUID()
        every { skillRepository.findById(id) } returns Optional.empty()

        val ex = assertThrows<ResponseStatusException> {
            service.updateSkill(id, UpdateSkillRequest(name = "Go", description = null, roleId = null))
        }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `retireSkill marks skill as RETIRED`() {
        val s = skill()
        every { skillRepository.findById(s.id) } returns Optional.of(s)
        every { skillRepository.save(any()) } answers { firstArg() }

        service.retireSkill(s.id)

        assertEquals(SkillStatus.RETIRED, s.status)
        verify(exactly = 1) { skillRepository.save(s) }
    }

    @Test
    fun `retireSkill throws 404 when skill not found`() {
        val id = UUID.randomUUID()
        every { skillRepository.findById(id) } returns Optional.empty()

        val ex = assertThrows<ResponseStatusException> { service.retireSkill(id) }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `assessSkillForMe removes old assessment and saves new one`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            authId = "auth1",
            username = "alice",
            firstname = "Alice",
            lastname = "Test",
            email = null,
        )
        val s = skill()
        val oldAssessment =
            UserSkillAssessment(id = UUID.randomUUID(), user = user, skill = s, level = SkillLevel.BEGINNER)
        user.skillAssessments.add(oldAssessment)

        val request = CreateSkillAssessmentRequest(skillId = s.id, level = SkillLevel.EXPERT)

        every { userRepository.findByAuthId("auth1") } returns Optional.of(user)
        every { skillRepository.findById(s.id) } returns Optional.of(s)
        every { userRepository.save(any()) } answers { firstArg() }

        val result = service.assessSkillForMe("auth1", request)

        assertEquals(SkillLevel.EXPERT, result.level)
        assertEquals(1, user.skillAssessments.size)
        verify(exactly = 1) { userRepository.save(user) }
    }

    @Test
    fun `assessSkillForMe throws 400 when skill is retired`() {
        val user = User(
            id = UUID.randomUUID(),
            authId = "auth1",
            username = "alice",
            firstname = "Alice",
            lastname = "Test",
            email = null,
        )
        val s = skill(status = SkillStatus.RETIRED)
        val request = CreateSkillAssessmentRequest(skillId = s.id, level = SkillLevel.BEGINNER)

        every { userRepository.findByAuthId("auth1") } returns Optional.of(user)
        every { skillRepository.findById(s.id) } returns Optional.of(s)

        val ex = assertThrows<ResponseStatusException> { service.assessSkillForMe("auth1", request) }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Test
    fun `getUserSkillAssessments returns mapped assessments`() {
        val userId = UUID.randomUUID()
        val user = User(
            id = userId,
            authId = "auth1",
            username = "alice",
            firstname = "Alice",
            lastname = "Test",
            email = null,
        )
        val s = skill()
        val assessment = UserSkillAssessment(id = UUID.randomUUID(), user = user, skill = s, level = SkillLevel.EXPERT)

        every { userRepository.existsById(userId) } returns true
        every { userSkillAssessmentRepository.findByUserId(userId) } returns listOf(assessment)

        val result = service.getUserSkillAssessments(userId)

        assertEquals(1, result.size)
        assertEquals(s.id, result[0].skillId)
        assertEquals(SkillLevel.EXPERT, result[0].level)
    }
}
