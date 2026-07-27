package com.sprintstart.sprintstartbackend.connectors.jira.controller

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.config.ConfigureAllJiraInstancesRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.config.ConfigureJiraInstanceRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.config.GetJiraInstanceConfigResponse
import com.sprintstart.sprintstartbackend.connectors.jira.service.JiraInstanceConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/jira/config")
internal class JiraInstanceConfigController(
    private val configService: JiraInstanceConfigService,
) {
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PutMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PM')")
    fun configureAll(
        @Valid @RequestBody request: ConfigureAllJiraInstancesRequest,
    ): ResponseEntity<Unit> {
        configService.configureAll(request)
        return ResponseEntity.noContent().build()
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('PM')")
    fun getAll(): ResponseEntity<List<GetJiraInstanceConfigResponse>> {
        val response = configService.getAll()
        return ResponseEntity.ok(response)
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PutMapping("/configure")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PM')")
    fun configureInstance(
        @Valid @RequestBody request: ConfigureJiraInstanceRequest,
    ): ResponseEntity<Unit> {
        configService.configureInstance(request)
        return ResponseEntity.noContent().build()
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/{instanceId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('PM')")
    fun getConfigOfInstance(
        @PathVariable instanceId: String,
    ): ResponseEntity<GetJiraInstanceConfigResponse> {
        val result = configService.getConfigOfInstance(instanceId)
        return ResponseEntity.ok(result)
    }
}
