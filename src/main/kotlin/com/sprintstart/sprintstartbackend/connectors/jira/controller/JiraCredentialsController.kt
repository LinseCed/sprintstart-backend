package com.sprintstart.sprintstartbackend.connectors.jira.controller

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/jira/credentials")
internal class JiraCredentialsController {
    @PostMapping
    fun addCredentials() {
    }
}
