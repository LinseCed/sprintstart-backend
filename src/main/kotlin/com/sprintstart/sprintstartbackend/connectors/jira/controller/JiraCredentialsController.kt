package com.sprintstart.sprintstartbackend.connectors.jira.controller

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials.AddCredentialRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials.ChangeJiraCredentialNameRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials.ChangeJiraCredentialTokenRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials.DeleteJiraCredentialRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.credentials.JiraCredentialsDto
import com.sprintstart.sprintstartbackend.connectors.jira.service.JiraCredentialsService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/jira/credentials")
internal class JiraCredentialsController(
    private val credentialsService: JiraCredentialsService,
) {
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping
    @PreAuthorize("hasRole('PM') or hasRole('ADMIN')")
    fun addCredentials(@RequestBody @Valid request: AddCredentialRequest): ResponseEntity<Unit> {
        credentialsService.addCredentials(request)
        return ResponseEntity.noContent().build()
    }

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/{userEmail}")
    @PreAuthorize("hasRole('PM') or hasRole('ADMIN')")
    fun getCredentialsOfUser(@PathVariable userEmail: String): ResponseEntity<List<JiraCredentialsDto>> {
        val response = credentialsService.getCredentialsOfUser(userEmail)
        return ResponseEntity.ok(response)
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping
    @PreAuthorize("hasRole('PM') or hasRole('ADMIN')")
    fun removeCredential(@RequestBody @Valid request: DeleteJiraCredentialRequest): ResponseEntity<Unit> {
        credentialsService.removeCredentials(request)
        return ResponseEntity.noContent().build()
    }

    @ResponseStatus(HttpStatus.OK)
    @PatchMapping("/patch/name")
    @PreAuthorize("hasRole('PM') or hasRole('ADMIN')")
    fun changeCredentialName(
        @RequestBody @Valid request: ChangeJiraCredentialNameRequest,
    ): ResponseEntity<JiraCredentialsDto> {
        val response = credentialsService.changeCredentialName(request)
        return ResponseEntity.ok(response)
    }

    @ResponseStatus(HttpStatus.OK)
    @PatchMapping("/patch/token")
    @PreAuthorize("hasRole('PM') or hasRole('ADMIN')")
    fun changeCredentialToken(
        @RequestBody @Valid request: ChangeJiraCredentialTokenRequest,
    ): ResponseEntity<JiraCredentialsDto> {
        val response = credentialsService.changeCredentialToken(request)
        return ResponseEntity.ok(response)
    }
}
