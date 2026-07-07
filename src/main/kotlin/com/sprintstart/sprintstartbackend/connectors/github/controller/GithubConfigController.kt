package com.sprintstart.sprintstartbackend.connectors.github.controller

import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.ConfigureRepositoryRequest
import com.sprintstart.sprintstartbackend.connectors.github.service.GithubConfigService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Validated
@Tag(
    name = "Github config management",
    description = "Endpoints for configuring the behaviour of the GitHub connector",
)
@RestController
@RequestMapping("/api/v1/github/config")
class GithubConfigController(
    private val configService: GithubConfigService,
) {
    @PutMapping("/global")
    fun configureGlobal(
        @Valid @RequestBody request: ConfigureRepositoryRequest,
    ): ResponseEntity<Unit> {
        configService.configureGlobal(request)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/{owner}/{name}")
    fun configureRepository(
        @PathVariable("owner") owner: String,
        @PathVariable("name") name: String,
        @Valid @RequestBody request: ConfigureRepositoryRequest,
    ): ResponseEntity<Unit> {
        configService.configure(owner, name, request)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{owner}/{name}")
    fun getConfigOfRepository(
        @PathVariable("owner") owner: String,
        @PathVariable("name") name: String,
    ): ReponseEntity<Unit> {
        val result = configService.findConfigByRepositoryOwnerAndName(owner, name)
    }
}
