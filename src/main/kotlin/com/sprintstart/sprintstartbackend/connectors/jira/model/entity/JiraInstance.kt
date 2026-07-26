package com.sprintstart.sprintstartbackend.connectors.jira.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "jira_instances")
internal class JiraInstance(
    @Id
    @Column(name = "instance_url", unique = true, nullable = false)
    var instanceUrl: String,
    @Column(name = "display_name")
    var displayName: String,
    @Column(name = "last_update", nullable = false)
    var lastUpdate: Instant = Instant.now(),
    @Column(name = "project_id", nullable = false)
    var projectIds: MutableSet<UUID> = mutableSetOf(),
    @Column(name = "source_enabled", nullable = false)
    var sourceEnabled: Boolean = false,
)
