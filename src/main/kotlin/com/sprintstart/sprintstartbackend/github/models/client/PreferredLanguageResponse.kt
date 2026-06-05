package com.sprintstart.sprintstartbackend.github.models.client

import kotlinx.serialization.Serializable

@Serializable
data class PreferredLanguageResponse(
    val data: JsonResponseBlob,
)

@Serializable
data class JsonResponseBlob(
    val repository: RepositoryMetadataBlob,
)

@Serializable
data class RepositoryMetadataBlob(
    val name: String,
    val primaryLanguage: LanguageBlob
)

@Serializable
data class LanguageBlob(
    val name: String,
)
