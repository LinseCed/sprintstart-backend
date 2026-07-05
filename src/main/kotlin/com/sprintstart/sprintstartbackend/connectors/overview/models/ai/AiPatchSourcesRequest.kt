package com.sprintstart.sprintstartbackend.connectors.overview.models.ai

data class AiPatchSourcesRequest(
    val connector: String,
    val sources: Map<String, Boolean>, // sourceId -> newStatus
)
