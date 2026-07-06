package com.sprintstart.sprintstartbackend.user.model.mapper

import com.sprintstart.sprintstartbackend.user.model.dto.SkillDto
import com.sprintstart.sprintstartbackend.user.model.entity.Skill

fun Skill.toDto() = SkillDto(
    id = id,
    name = name,
    roleId = projectRole.id,
    description = description,
    status = status,
)
