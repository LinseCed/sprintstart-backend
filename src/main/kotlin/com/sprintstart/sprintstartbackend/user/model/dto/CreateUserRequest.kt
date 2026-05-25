package com.sprintstart.sprintstartbackend.user.model.dto

import com.sprintstart.sprintstartbackend.user.external.enums.WorkingAreas

data class CreateUserRequest(
    val username: String,
    val firstname: String,
    val lastname: String,
    val workingArea: WorkingAreas,
)
