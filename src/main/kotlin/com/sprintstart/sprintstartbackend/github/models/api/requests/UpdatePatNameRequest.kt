package com.sprintstart.sprintstartbackend.github.models.api.requests

data class UpdatePatNameRequest(
    val oldName: String,
    val newName: String,
)
