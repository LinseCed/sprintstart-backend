package com.sprintstart.sprintstartbackend.upload.external.events

import kotlinx.serialization.Serializable

@Serializable
data class AiIngestResponse(
    val success: Boolean,
)
