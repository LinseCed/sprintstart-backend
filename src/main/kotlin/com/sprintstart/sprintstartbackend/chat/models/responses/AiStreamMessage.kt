package com.sprintstart.sprintstartbackend.chat.models.responses

import kotlinx.serialization.Serializable

@Serializable
data class AiStreamMessage(
    val type: String,
    val content: String? = null
)
