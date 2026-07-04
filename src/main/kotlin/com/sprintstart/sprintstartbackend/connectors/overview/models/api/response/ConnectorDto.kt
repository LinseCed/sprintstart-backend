package com.sprintstart.sprintstartbackend.connectors.overview.models.api.response

import java.time.Instant

/**
 * The DTO holding all available information about connectors.
 *
 * @param id The id of the connector.
 * @param name The display name of the connector.
 * @param enabled Whether the connector is currently enabled or not.
 * @param lastEnabledAt A timestamp of the last time the connector was enabled.
 */
data class ConnectorDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val lastEnabledAt: Instant?,
)
