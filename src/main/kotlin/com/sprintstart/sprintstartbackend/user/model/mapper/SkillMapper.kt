package com.sprintstart.sprintstartbackend.user.model.mapper

import com.sprintstart.sprintstartbackend.user.model.entity.Skill
import com.sprintstart.sprintstartbackend.user.model.response.skill.SkillDto

fun Skill.toDto() = SkillDto(
    id = id,
    name = name,
    roleId = projectRole.id,
    description = description,
    status = status,
)
