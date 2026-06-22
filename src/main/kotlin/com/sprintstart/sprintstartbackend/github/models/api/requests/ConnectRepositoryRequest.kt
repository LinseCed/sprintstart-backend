package com.sprintstart.sprintstartbackend.github.models.api.requests

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * Represents a request to connect a GitHub repository.
 */
data class ConnectRepositoryRequest(
    @NotBlank
    val owner: String,
    @NotBlank
    val name: String,
    @Pattern(regexp = """^ghp_[a-zA-Z0-9]{36}$""")
    val tokenName: String,
)
