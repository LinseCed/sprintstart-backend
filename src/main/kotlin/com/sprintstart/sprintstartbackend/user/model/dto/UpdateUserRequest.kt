package com.sprintstart.sprintstartbackend.user.model.dto

data class UpdateUserEnabledRequest(
    val enabled: Boolean,
)

data class DeleteUserResponse(
    val id: java.util.UUID,
    val deleted: Boolean = true,
)
