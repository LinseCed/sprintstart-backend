package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.user.external.enums.SkillStatus
import com.sprintstart.sprintstartbackend.user.model.entity.ProjectRole
import com.sprintstart.sprintstartbackend.user.model.entity.Skill
import com.sprintstart.sprintstartbackend.user.model.entity.UserSkillAssessment
import com.sprintstart.sprintstartbackend.user.model.mapper.toDto
import com.sprintstart.sprintstartbackend.user.model.mapper.toGetResponse
import com.sprintstart.sprintstartbackend.user.model.request.skill.CreateSkillAssessmentRequest
import com.sprintstart.sprintstartbackend.user.model.request.skill.CreateSkillRequest
import com.sprintstart.sprintstartbackend.user.model.request.skill.UpdateSkillRequest
import com.sprintstart.sprintstartbackend.user.model.response.skill.GetSkillAssessmentResponse
import com.sprintstart.sprintstartbackend.user.model.response.skill.SkillAssessmentDto
import com.sprintstart.sprintstartbackend.user.model.response.skill.SkillDto
import com.sprintstart.sprintstartbackend.user.repository.ProjectRoleRepository
import com.sprintstart.sprintstartbackend.user.repository.SkillRepository
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import com.sprintstart.sprintstartbackend.user.repository.UserSkillAssessmentRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class SkillService(
    private val skillRepository: SkillRepository,
    private val projectRoleRepository: ProjectRoleRepository,
    private val userRepository: UserRepository,
    private val userSkillAssessmentRepository: UserSkillAssessmentRepository,
) {
    @Transactional(readOnly = true)
    fun getAllSkills(): List<SkillDto> {
        return skillRepository.findAll().map { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun getSkillById(skillId: UUID): SkillDto {
        return skillRepository
            .findById(skillId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Skill with id $skillId not found") }
            .toDto()
    }

    @Transactional
    fun createSkill(request: CreateSkillRequest): SkillDto {
        if (skillRepository.existsByNormalizedName(request.name)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Skill with name '${request.name}' already exists")
        }

        val roles = findRolesByIds(request.roleIds)

        return skillRepository.save(Skill(name = request.name, projectRoles = roles)).toDto()
    }

    @Transactional
    fun updateSkill(skillId: UUID, request: UpdateSkillRequest): SkillDto {
        val skill = skillRepository
            .findById(skillId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Skill with id $skillId not found") }

        request.name?.let { newName ->
            if (skillRepository.existsByNormalizedNameExcluding(newName, skillId)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Skill with name '$newName' already exists")
            }
            skill.name = newName
        }

        request.description?.let { skill.description = it }

        request.roleIds?.let { roleIds ->
            skill.projectRoles = findRolesByIds(roleIds)
        }

        return skillRepository.save(skill).toDto()
    }

    private fun findRolesByIds(roleIds: List<UUID>): MutableSet<ProjectRole> {
        val roles = projectRoleRepository.findAllById(roleIds)
        val foundIds = roles.map { it.id }.toSet()
        val missingIds = roleIds.toSet() - foundIds
        if (missingIds.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project role(s) with id(s) $missingIds not found")
        }
        return roles.toMutableSet()
    }

    /**
     * Retires a skill so it can no longer be assigned to new users.
     * Existing assessments referencing this skill are preserved.
     */
    @Transactional
    fun retireSkill(skillId: UUID) {
        val skill = skillRepository
            .findById(skillId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Skill with id $skillId not found") }

        skill.status = SkillStatus.RETIRED
        skillRepository.save(skill)
    }

    @Transactional(readOnly = true)
    fun getUserSkillAssessments(userId: UUID): List<SkillAssessmentDto> {
        if (!userRepository.existsById(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "User with id $userId not found")
        }
        return userSkillAssessmentRepository.findByUserId(userId).map { it.toDto() }
    }

    @Transactional
    fun getMySkillAssessments(authId: String): List<GetSkillAssessmentResponse> {
        val user = userRepository
            .findByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User with authId $authId not found") }

        val assessments = userSkillAssessmentRepository
            .findByUserId(user.id)
            .map { it.toGetResponse() }

        return assessments
    }

    @Transactional
    fun assessSkillForMe(authId: String, request: CreateSkillAssessmentRequest): SkillAssessmentDto {
        val user = userRepository
            .findByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User with authId $authId not found") }

        val skill = skillRepository
            .findById(request.skillId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Skill with id ${request.skillId} not found") }

        if (skill.status == SkillStatus.RETIRED) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Skill '${skill.name}' is retired and cannot be assigned",
            )
        }

        user.skillAssessments.removeIf { it.skill.id == skill.id }

        val assessment = UserSkillAssessment(
            user = user,
            skill = skill,
            level = request.level,
        )

        user.skillAssessments.add(assessment)
        userRepository.save(user)

        val savedAssessment = user.skillAssessments.first { it.skill.id == skill.id }
        return savedAssessment.toDto()
    }
}
