package com.sprintstart.sprintstartbackend.user.model.dto

import com.sprintstart.sprintstartbackend.user.external.enums.Roles
import com.sprintstart.sprintstartbackend.user.external.enums.WorkingAreas
import java.util.UUID

data class GetUserResponse(
    val id: UUID,
    val username: String,
    val firstname: String,
    val lastname: String,
    val primaryRole: Roles,
    val secondaryRole: Roles,
    val workingArea: WorkingAreas,
)
