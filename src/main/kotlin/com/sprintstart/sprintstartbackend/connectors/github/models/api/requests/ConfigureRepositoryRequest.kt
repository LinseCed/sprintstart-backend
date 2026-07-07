package com.sprintstart.sprintstartbackend.connectors.github.models.api.requests

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern

data class ConfigureRepositoryRequest(
    val schedule: UpdateSchedule,
    val autoUpdate: Boolean,
)

data class UpdateSchedule(
    val seconds: List<
            @Pattern(regexp = "^(\\*|[0-9]|[1-5][0-9])$")
            String
            >,
    val minutes: List<
            @Pattern(regexp = "^(\\*|[0-9]|[1-5][0-9])$")
            String
            >,
    val hour: List<
            @Pattern(regexp = "^(\\*|[0-9]|[1-2][0-3])$")
            String
            >,
    val dayOfWeek: List<
            @Pattern(regexp = "^(\\*|[1-7])$")
            String
            >,
    val dayOfMonth: List<
            @Pattern(regexp = "^(\\*|[1-9]|[1-2][0-9]|3[0-1])$")
            String
            >,
    val monthOfYear: List<
            @Pattern(regexp = "^(\\*|[1-9]|1[0-2])$")
            String
            >,
)

