package com.sprintstart.sprintstartbackend.connectors.github.service

import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.ConfigureRepositoryRequest
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubConfigRepository
import org.springframework.stereotype.Service

@Service
class GithubConfigService(
    private val configRepository: GithubConfigRepository,
) {
    fun configureGlobal(request: ConfigureRepositoryRequest) {
        // TODO
    }

    fun configure(owner: String, name: String, request: ConfigureRepositoryRequest) {
        // TODO
    }
}
