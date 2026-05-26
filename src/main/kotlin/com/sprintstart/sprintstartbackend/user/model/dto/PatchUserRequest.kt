package com.sprintstart.sprintstartbackend.user.model.dto

import com.sprintstart.sprintstartbackend.user.external.enums.Role
import com.sprintstart.sprintstartbackend.user.external.enums.WorkingArea

data class PatchUserRequest(
    val username: String?,
    val firstname: String?,
    val lastname: String?,
    val primaryRole: Role?,
    val secondaryRole: Role?,
    val workingArea: WorkingArea?,
)
