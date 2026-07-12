package com.sprintstart.sprintstartbackend.connectors.github.models.api.requests

import jakarta.validation.constraints.Pattern

/**
 * Represents a request to configure settings for a GitHub repository.
 *
 * @property schedule The update schedule defining the timing of updates for the repository.
 * @property autoUpdate A flag indicating whether automatic updates are enabled for the repository.
 */
data class ConfigureRepositoryRequest(
    val schedule: UpdateSchedule,
    val autoUpdate: Boolean,
)

/**
 * Represents a schedule for defining update timings using cron-like patterns.
 *
 * @property seconds Specifies the seconds field for the schedule. Accepts a wildcard (*) or values between 0-59.
 * @property minutes Specifies the minutes field for the schedule. Accepts a wildcard (*) or values between 0-59.
 * @property hour Specifies the hour field for the schedule. Accepts a wildcard (*) or values between 0-23.
 * @property dayOfWeek Specifies the day of the week field for the schedule. Accepts a wildcard (*) or values between
 * 1-7 (where 1 is Sunday).
 * @property dayOfMonth Specifies the day of the month field for the schedule. Accepts a wildcard (*) or values between
 * 1-31.
 * @property monthOfYear Specifies the month of the year field for the schedule. Accepts a wildcard (*) or values
 * between 1-12.
 */
data class UpdateSchedule(
    val seconds: List<
        @Pattern(regexp = "^(\\*|[0-9]|[1-5][0-9])$")
        String,
    >,
    val minutes: List<
        @Pattern(regexp = "^(\\*|[0-9]|[1-5][0-9])$")
        String,
    >,
    val hour: List<
        @Pattern(regexp = "^(\\*|[0-9]|[1-2][0-3])$")
        String,
    >,
    val dayOfWeek: List<
        @Pattern(regexp = "^(\\*|[1-7])$")
        String,
    >,
    val dayOfMonth: List<
        @Pattern(regexp = "^(\\*|[1-9]|[1-2][0-9]|3[0-1])$")
        String,
    >,
    val monthOfYear: List<
        @Pattern(regexp = "^(\\*|[1-9]|1[0-2])$")
        String,
    >,
)
