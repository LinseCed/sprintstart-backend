package com.sprintstart.sprintstartbackend.user.model.dto

data class KeycloakEventRequest(
    val type: String,
    val userId: String,
    val details: Map<String, String> = emptyMap(),
)
