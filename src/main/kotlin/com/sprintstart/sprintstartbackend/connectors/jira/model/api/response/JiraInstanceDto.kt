package com.sprintstart.sprintstartbackend.connectors.jira.model.api.response

import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstance
import java.time.Instant
import java.util.UUID

internal data class JiraInstanceDto(
    var instanceUrl: String,
    var displayName: String,
    var lastUpdate: Instant,
    var projectIds: MutableSet<UUID>,
    var sourceEnabled: Boolean,
)

internal fun JiraInstance.toDto() = JiraInstanceDto(
    instanceUrl = this.instanceUrl,
    displayName = this.displayName,
    lastUpdate = this.lastUpdate,
    projectIds = this.projectIds,
    sourceEnabled = this.sourceEnabled,
)
