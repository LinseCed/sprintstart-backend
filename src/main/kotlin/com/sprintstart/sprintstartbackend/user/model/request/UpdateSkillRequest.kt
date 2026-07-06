package com.sprintstart.sprintstartbackend.user.model.request

import java.util.UUID

data class UpdateSkillRequest(
    val name: String?,
    val description: String?,
    val roleId: UUID?,
)
