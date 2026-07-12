package com.sprintstart.sprintstartbackend.chat.models

import com.sprintstart.sprintstartbackend.ingestion.model.entity.SourceSystem
import kotlinx.serialization.Serializable

@Serializable
class AiChatFilters(
    var source_systems: List<SourceSystem>?,
    var time_from: String?,
    var time_to: String?,
)
