package com.sprintstart.sprintstartbackend.upload.external.events

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AiIngestRequest(
    @Contextual
    val artifactId: UUID,
    val filename: String,
    val content: String,
)
