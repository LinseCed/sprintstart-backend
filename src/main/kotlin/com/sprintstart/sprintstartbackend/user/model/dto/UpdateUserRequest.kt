package com.sprintstart.sprintstartbackend.user.model.dto

import com.sprintstart.sprintstartbackend.user.external.enums.Roles
import com.sprintstart.sprintstartbackend.user.external.enums.WorkingAreas

data class UpdateUserRequest(
    val username: String,
    val firstname: String,
    val lastname: String,
    val primaryRole: Roles,
    val secondaryRole: Roles,
    val workingArea: WorkingAreas
)
