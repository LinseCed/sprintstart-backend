package com.sprintstart.sprintstartbackend.connectors.overview.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "connector_configurations")
class ConnectorConfiguration(
    @Id
    var id: String,
    @Column(nullable = false)
    var enabled: Boolean = false,
    @Column(name = "last_enabled_at")
    var lastEnabledAt: Instant? = null,
)
