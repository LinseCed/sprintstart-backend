package com.sprintstart.sprintstartbackend.user.model.response.skill

import com.sprintstart.sprintstartbackend.user.external.enums.SkillStatus
import java.util.UUID

data class SkillDto(
    val id: UUID,
    val name: String,
    val roleId: UUID,
    val description: String?,
    val status: SkillStatus,
)
