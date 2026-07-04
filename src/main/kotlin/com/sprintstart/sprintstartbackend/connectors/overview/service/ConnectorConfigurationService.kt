package com.sprintstart.sprintstartbackend.connectors.overview.service

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.connectors.overview.SourceClient
import com.sprintstart.sprintstartbackend.connectors.overview.models.IConnector
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.request.ConfigureConnectorRequest
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.request.PatchSourcesRequest
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.ConfigureConnectorResponse
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.GetSourcesOfConnectorResponse
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.toConfigureConnectorResponse
import com.sprintstart.sprintstartbackend.connectors.overview.models.exceptions.ConnectorConfigurationNotFoundException
import com.sprintstart.sprintstartbackend.connectors.overview.models.exceptions.ConnectorNotFoundException
import com.sprintstart.sprintstartbackend.connectors.overview.repository.ConnectorConfigurationRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import org.springframework.stereotype.Service

@Service
class ConnectorConfigurationService(
    private val applicationConfig: ApplicationConfig,
    private val repository: ConnectorConfigurationRepository,
    private val connectors: List<IConnector>,
    private val sourceClient: SourceClient,
) {
    @Tracked("Configuring new connector")
    fun configure(connectorId: String, request: ConfigureConnectorRequest): ConfigureConnectorResponse {
        val configuration = repository.findById(connectorId).orElseThrow {
            ConnectorConfigurationNotFoundException(
                "Could not find configuration for connector $connectorId",
            )
        }

        configuration.enabled = request.enabled
        return repository.save(configuration).toConfigureConnectorResponse()
    }

    @Tracked("Retrieving all sources of given connector")
    fun getSourcesOfConnector(connectorId: String): GetSourcesOfConnectorResponse {
        val connector = connectors.stream().filter { it.id == connectorId }.findFirst().orElseThrow {
            ConnectorNotFoundException("Unable to load up connector with id $connectorId")
        }

        return GetSourcesOfConnectorResponse(
            connectorId = connectorId,
            sources = connector.getSources(),
        )
    }

    @Tracked("Patching requested sources statuses of connector")
    suspend fun patchSourcesIfExists(connectionId: String, request: PatchSourcesRequest) {
        val connector = connectors.find { it.id == connectionId }
            ?: throw ConnectorNotFoundException("Unable to find connector with id $connectionId")
        val idStatusesMap = request.sources.associateBy({ it.sourceId }, { it.enabled })

        patchSources(connector, idStatusesMap)
    }

    private suspend fun patchSources(connector: IConnector, requestedSources: Map<String, Boolean>) {
        connector
            .getSources()
            .stream()
            .filter { source ->
                requestedSources.containsKey(source.id) && requestedSources[source.id] != null
            }.forEach { source ->
                connector.patchSource(source, requestedSources[source.id]!!)
            }

        sourceClient.patchSources(requestedSources)
    }
}
