package com.sprintstart.sprintstartbackend.github.models.api.requests

import kotlinx.serialization.Serializable

@Serializable
data class AddPatRequest(
    val name: String,
    val token: String,
)
