package com.sprintstart.sprintstartbackend.connectors.overview.controller

import com.sprintstart.sprintstartbackend.connectors.overview.models.IConnector
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.request.ConfigureConnectorRequest
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.request.PatchSourcesRequest
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.ConfigureConnectorResponse
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.ConnectorDto
import com.sprintstart.sprintstartbackend.connectors.overview.models.api.response.GetSourcesOfConnectorResponse
import com.sprintstart.sprintstartbackend.connectors.overview.service.ConnectorConfigurationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private const val ID_PATTERN = "^[a-z0-9-]+$"

@Validated
@RestController
@RequestMapping("/api/v1/connectors")
class ConnectorController(
    connectors: List<IConnector>,
    private val connectorConfigurationService: ConnectorConfigurationService,
) {
    // Verify all connector ids are truly unique on bean init
    init {
        val duplicates = connectors.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate connector IDs detected: $duplicates" }
    }

    @GetMapping
    fun listAll(): ResponseEntity<List<ConnectorDto>> =
        ResponseEntity.ok(connectorConfigurationService.findAllConnectors())

    @PatchMapping("/{id}")
    fun configureConnector(
        @Pattern(regexp = ID_PATTERN) @PathVariable id: String,
        @Valid @RequestBody request: ConfigureConnectorRequest,
    ): ResponseEntity<ConfigureConnectorResponse> =
        ResponseEntity.ok(connectorConfigurationService.configure(id, request))

    @GetMapping("/{id}/sources")
    fun getSourcesOfConnector(
        @Pattern(regexp = ID_PATTERN) @PathVariable id: String,
    ): ResponseEntity<GetSourcesOfConnectorResponse> =
        ResponseEntity.ok(connectorConfigurationService.getSourcesOfConnector(id))

    @PatchMapping("/{id}/sources/status")
    suspend fun patchSourcesOfConnector(
        @Pattern(regexp = ID_PATTERN) @PathVariable id: String,
        @Valid @RequestBody request: PatchSourcesRequest,
    ): ResponseEntity<Unit> {
        return ResponseEntity.ok(connectorConfigurationService.patchSourcesIfExists(id, request))
    }
}
