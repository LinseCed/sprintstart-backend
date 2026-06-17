package com.sprintstart.sprintstartbackend.github.models.client

import kotlinx.serialization.Serializable

@Serializable
data class AiIngestRequest(
    val id: String,
    val name: String,
    val content: String,
)
