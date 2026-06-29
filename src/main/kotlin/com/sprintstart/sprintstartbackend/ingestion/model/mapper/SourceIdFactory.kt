package com.sprintstart.sprintstartbackend.ingestion.model.mapper

import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType

object SourceIdFactory {
    fun buildSourceId(
        repositoryOwner: String,
        repositoryName: String,
        type: ArtifactType,
        unique: String?,
    ): String =
        "github:$repositoryOwner/$repositoryName:$type:$unique"
}
