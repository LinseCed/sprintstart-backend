package com.sprintstart.sprintstartbackend.user.model.mapper

import com.sprintstart.sprintstartbackend.user.model.entity.Skill
import com.sprintstart.sprintstartbackend.user.model.entity.UserSkillAssessment
import com.sprintstart.sprintstartbackend.user.model.response.skill.GetSkillAssessmentResponse
import com.sprintstart.sprintstartbackend.user.model.response.skill.SkillAssessmentDto
import com.sprintstart.sprintstartbackend.user.model.response.skill.SkillDto

fun Skill.toDto() = SkillDto(
    id = id,
    name = name,
    roleId = projectRole.id,
    description = description,
    status = status,
)

fun UserSkillAssessment.toDto() = SkillAssessmentDto(
    userId = user.id,
    skillId = skill.id,
    level = level,
)

fun UserSkillAssessment.toGetResponse() = GetSkillAssessmentResponse(
    id = id,
    userId = user.id,
    skillId = skill.id,
    level = level,
)
