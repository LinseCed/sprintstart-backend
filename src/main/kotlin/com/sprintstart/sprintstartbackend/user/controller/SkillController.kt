package com.sprintstart.sprintstartbackend.user.controller

import com.sprintstart.sprintstartbackend.user.model.dto.SkillAssessmentDto
import com.sprintstart.sprintstartbackend.user.model.dto.SkillDto
import com.sprintstart.sprintstartbackend.user.model.entity.Skill
import com.sprintstart.sprintstartbackend.user.model.entity.SkillLevel
import com.sprintstart.sprintstartbackend.user.model.entity.UserSkillAssessment
import com.sprintstart.sprintstartbackend.user.repository.ProjectRoleRepository
import com.sprintstart.sprintstartbackend.user.repository.SkillRepository
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import com.sprintstart.sprintstartbackend.user.repository.UserSkillAssessmentRepository
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateSkillRequest(
    val name: String,
    val roleId: UUID,
)

data class CreateSkillAssessmentRequest(
    val userId: UUID,
    val skillId: UUID,
    val level: SkillLevel,
)

@RestController
@RequestMapping("/api/v1")
class SkillController(
    private val skillRepository: SkillRepository,
    private val projectRoleRepository: ProjectRoleRepository,
    private val userRepository: UserRepository,
    private val userSkillAssessmentRepository: UserSkillAssessmentRepository,
) {
    @GetMapping("/skills")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR', 'USER')")
    fun getAllSkills(): List<SkillDto> {
        return skillRepository.findAll().map { skill ->
            SkillDto(
                id = skill.id,
                name = skill.name,
                roleId = skill.projectRole.id,
            )
        }
    }

    @PostMapping("/skills")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun createSkill(@RequestBody request: CreateSkillRequest): SkillDto {
        val role = projectRoleRepository.findById(request.roleId).orElseThrow()
        val skill = Skill(
            name = request.name,
            projectRole = role,
        )
        val savedSkill = skillRepository.save(skill)
        return SkillDto(
            id = savedSkill.id,
            name = savedSkill.name,
            roleId = savedSkill.projectRole.id,
        )
    }

    @DeleteMapping("/skills/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR')")
    fun deleteSkill(@PathVariable skillId: UUID) {
        skillRepository.deleteById(skillId)
    }

    @GetMapping("/users/{userId}/skill-assessments/completed")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR', 'USER')")
    fun getUserSkillAssessments(@PathVariable userId: UUID): List<SkillAssessmentDto> {
        return userSkillAssessmentRepository.findByUserId(userId).map { assessment ->
            SkillAssessmentDto(
                userId = assessment.user.id,
                skillId = assessment.skill.id,
                level = assessment.level,
            )
        }
    }

    @PostMapping("/skill-assessments")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('ADMIN', 'PM', 'HR', 'USER')")
    fun assessSkill(@RequestBody request: CreateSkillAssessmentRequest): SkillAssessmentDto {
        val user = userRepository.findById(request.userId).orElseThrow()
        val skill = skillRepository.findById(request.skillId).orElseThrow()

        // Remove existing assessment for this skill
        user.skillAssessments.removeIf { it.skill.id == skill.id }

        val assessment = UserSkillAssessment(
            user = user,
            skill = skill,
            level = request.level,
        )

        user.skillAssessments.add(assessment)
        userRepository.save(user)

        // After saving user, return the assessment DTO
        val savedAssessment = user.skillAssessments.first { it.skill.id == skill.id }
        return SkillAssessmentDto(
            userId = savedAssessment.user.id,
            skillId = savedAssessment.skill.id,
            level = savedAssessment.level,
        )
    }
}
