package com.sprintstart.sprintstartbackend.connectors.github.service

import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConfig
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.ConfigureRepositoryRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.GetRepositoryConfigRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.UpdateSchedule
import com.sprintstart.sprintstartbackend.connectors.github.models.api.responses.GetRepositoryConfigResponse
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryConfigNotFoundException
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryNotConnectedException
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryNotFoundException
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubConfigRepository
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.connectors.overview.models.ConnectorConfiguration
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Service
class GithubConfigService(
    private val configRepository: GithubConfigRepository,
    private val githubRepoRepository: GithubRepositoryConnectionRepository,
) {
    @Tracked("Configuring all GitHub repositories")
    fun configureGlobal(request: ConfigureRepositoryRequest) {
        val configs = configRepository.findAll()

        configs.forEach {
            it.autoUpdate = request.autoUpdate
            it.schedule = request.schedule.parseToString()
        }

        configRepository.saveAll(configs)
    }

    @Tracked("Retrieving config of GitHub repository")
    fun getConfigOfRepository(request: GetRepositoryConfigRequest): GetRepositoryConfigResponse {
        val config = findConfigByRepositoryOwnerAndName(request.owner, request.name)
        return GetRepositoryConfigResponse.of(config)
    }

    @Tracked("Configuring GitHub repository")
    fun configure(owner: String, name: String, request: ConfigureRepositoryRequest) {
        val config = findConfigByRepositoryOwnerAndName(owner, name)

        config.autoUpdate = request.autoUpdate
        config.schedule = request.schedule.parseToString()

        configRepository.save(config)
    }

    @Tracked("Retrieving all GitHub repositories due for sync now")
    fun findAllRepositoriesDueForSync(now: Instant): List<GithubRepositoryConnection> {
        val dueConfigs = configRepository.findAllDue(now)

        return dueConfigs.map {
            githubRepoRepository.findById(it.id).orElseThrow {
                RepositoryNotFoundException("", "", "Repository with id ${it.id} not found")
            }
        }
    }

    @Tracked("Retrieving GitHub repository config")
    fun findConfigById(id: UUID): Optional<GithubRepositoryConfig> = configRepository.findById(id)

    private fun findConfigByRepositoryOwnerAndName(owner: String, name: String): GithubRepositoryConfig {
        val repository = githubRepoRepository.findByOwnerAndName(owner, name)
            ?: throw RepositoryNotConnectedException(owner, name)

        return configRepository.findById(repository.id).orElseThrow {
            RepositoryConfigNotFoundException(owner, name)
        }
    }

    private fun UpdateSchedule.parseToString(): String {
        return StringBuilder()
            .append(this.seconds + " ")
            .append(this.minutes + " ")
            .append(this.hour + " ")
            .append(this.dayOfMonth + " ")
            .append(this.monthOfYear + " ")
            .append(this.dayOfWeek)
            .toString()
    }
}
