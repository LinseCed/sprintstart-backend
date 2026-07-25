package com.sprintstart.sprintstartbackend.connectors.jira.controller

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.ConnectJiraInstanceRequest
import com.sprintstart.sprintstartbackend.connectors.jira.service.JiraService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/jira")
class JiraController(
    private val service: JiraService,
) {
    @PostMapping("/connect")
    suspend fun connectInstance(@Valid request: ConnectJiraInstanceRequest): ResponseEntity<Unit> {
        service.connectInstanceIfExists(request)
        return ResponseEntity.accepted().build()
    }
}
