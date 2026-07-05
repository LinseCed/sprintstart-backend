package com.sprintstart.sprintstartbackend.connectors.overview.service

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.connectors.overview.SourceClient
import com.sprintstart.sprintstartbackend.connectors.overview.models.ConnectorConfiguration
import com.sprintstart.sprintstartbackend.connectors.overview.models.IConnector
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.request.ConfigureConnectorRequest
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.request.PatchSourcesRequest
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.ConfigureConnectorResponse
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.ConnectorDto
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.GetSourcesOfConnectorResponse
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.toConfigureConnectorResponse
import com.sprintstart.sprintstartbackend.connectors.overview.models.exceptions.ConnectorNotFoundException
import com.sprintstart.sprintstartbackend.connectors.overview.repository.ConnectorConfigurationRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ConnectorConfigurationService(
    private val applicationConfig: ApplicationConfig,
    private val repository: ConnectorConfigurationRepository,
    private val connectors: List<IConnector>,
    private val sourceClient: SourceClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun ensureAllConnectorsHaveConfig() {
        logger.info("Synchronizing connectors with db")
        val savedConnectorIds = repository.findAll().map { it.id }.toSet()
        val connectorIds = connectors.map { it.id }.toSet()

        val insertables = connectorIds - savedConnectorIds
        val deletables = savedConnectorIds - connectorIds

        // Insert all connectors that are defined via the `IConnector` interface but have no db entry
        insertables.forEach { connectorId ->
            repository.findById(connectorId).orElseGet {
                logger.info("Found missing connector db entry '$connectorId' - inserting")
                repository.save(ConnectorConfiguration(id = connectorId))
            }
        }

        // Delete all connector db entries that are not defined via the `IConnector` interface anymore
        deletables.forEach { connectorId ->
            logger.info("Found deprecated connector db entry '$connectorId' - deleting")
            repository.deleteById(connectorId)
        }
    }

    @Tracked("Retrieving all connectors")
    @Transactional(readOnly = true)
    fun findAllConnectors(): List<ConnectorDto> {
        return repository.findAll().map { config ->
            val iConnector = connectors.find { it.id == config.id }
                ?: throw RuntimeException("Connector db state is desynced with soft state. Should not happen.")

            ConnectorDto(
                config.id,
                iConnector.displayName,
                config.enabled,
                config.firstConfiguredAt,
                config.lastConfiguredAt,
            )
        }
    }

    @Tracked("Configuring new connector")
    fun configure(connectorId: String, request: ConfigureConnectorRequest): ConfigureConnectorResponse {
        val connector = connectors.find { it.id == connectorId }
            ?: throw ConnectorNotFoundException("No connector with id $connectorId")
        val configuration = repository.findById(connectorId).get()

        configuration.enabled = request.enabled
        configuration.lastConfiguredAt = Instant.now()

        if (configuration.firstConfiguredAt == null) {
            configuration.firstConfiguredAt = Instant.now()
        }

        return repository.save(configuration).toConfigureConnectorResponse()
    }

    @Tracked("Retrieving all sources of given connector")
    @Transactional(readOnly = true)
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
    suspend fun patchSourcesIfConnectorExists(connectionId: String, request: PatchSourcesRequest) {
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
