package com.sprintstart.sprintstartbackend.connectors.jira

import com.sprintstart.sprintstartbackend.connectors.jira.service.JiraService
import com.sprintstart.sprintstartbackend.connectors.overview.models.ConnectorSource
import com.sprintstart.sprintstartbackend.connectors.overview.models.IConnector
import org.springframework.stereotype.Component

@Component
class JiraConnector(
    private val service: JiraService,
) : IConnector {
    override val id: String
        get() = TODO("Not yet implemented")
    override val displayName: String
        get() = TODO("Not yet implemented")

    override fun getSources(): List<ConnectorSource> {
        TODO("Not yet implemented")
    }

    override fun patchSource(
        source: ConnectorSource,
        newStatus: Boolean,
    ) {
        TODO("Not yet implemented")
    }
}
