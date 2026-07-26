package com.sprintstart.sprintstartbackend.connectors.jira.controller

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.ConnectJiraInstanceRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.JiraInstanceDto
import com.sprintstart.sprintstartbackend.connectors.jira.service.JiraService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(
    name = "Jira Connector",
    description = "Endpoints for interacting with the Jira connector",
)
@Validated
@RestController
@RequestMapping("/api/v1/jira")
internal class JiraController(
    private val service: JiraService,
) {
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/instances")
    @PreAuthorize("hasRole('PM') or hasRole('ADMIN')")
    fun getInstances(@RequestParam(required = false) projectId: UUID?): ResponseEntity<List<JiraInstanceDto>> {
        val response = if (projectId != null) {
            service.getInstances(projectId)
        } else {
            service.getInstances()
        }
        return ResponseEntity.ok(response)
    }

    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/connect")
    @PreAuthorize("hasRole('PM') or hasRole('ADMIN')")
    suspend fun connectInstance(@Valid request: ConnectJiraInstanceRequest): ResponseEntity<Unit> {
        service.connectInstanceIfNeeded(request)
        return ResponseEntity.accepted().build()
    }
}
