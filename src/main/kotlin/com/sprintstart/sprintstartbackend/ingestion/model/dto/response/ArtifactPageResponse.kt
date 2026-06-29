package com.sprintstart.sprintstartbackend.canonical.model.dto.response

import com.sprintstart.sprintstartbackend.ingestion.model.dto.response.ArtifactResponse

data class ArtifactPageResponse(
    val items: List<ArtifactResponse>,
    val page: PageMetadata,
)
