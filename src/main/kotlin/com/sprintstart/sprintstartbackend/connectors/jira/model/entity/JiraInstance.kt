package com.sprintstart.sprintstartbackend.connectors.jira.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "jira_instances")
class JiraInstance(
    @Id
    @Column(name = "display_name")
    var displayName: String,
    @Column(name = "instance_url", unique = true, nullable = false)
    var instanceUrl: String,
    @Column(name = "last_update")
    var lastUpdate: Instant? = null,
)
