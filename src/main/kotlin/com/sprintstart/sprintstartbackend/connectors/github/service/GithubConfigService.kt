package com.sprintstart.sprintstartbackend.connectors.github.service

import com.sprintstart.sprintstartbackend.connectors.github.models.GithubConfig
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.ConfigureRepositoryRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.UpdateSchedule
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryConfigNotFoundException
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryNotConnectedException
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryNotFoundException
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubConfigRepository
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Service
class GithubConfigService(
    private val configRepository: GithubConfigRepository,
    private val githubRepoRepository: GithubRepositoryConnectionRepository
) {
    @Tracked("Configuring all GitHub repositories")
    fun configureGlobal(request: ConfigureRepositoryRequest) {
        val configs = configRepository.findAll()

        configs.forEach {
            it.autoUpdate = request.autoUpdate
            it.schedule = parseSchedule(request.schedule)
        }

        configRepository.saveAll(configs)
    }

    @Tracked("Retrieving config of GitHub repository")
    fun findConfigByRepositoryOwnerAndName(owner: String, name: String): GithubConfig {
        val repository = githubRepoRepository.findByOwnerAndName(owner, name)
            ?: throw RepositoryNotConnectedException(owner, name)

        return configRepository.findById(repository.id).orElseThrow {
            RepositoryConfigNotFoundException(owner, name)
        }
    }

    @Tracked("Configuring GitHub repository")
    fun configure(owner: String, name: String, request: ConfigureRepositoryRequest) {
        val config = findConfigByRepositoryOwnerAndName(owner, name)

        config.autoUpdate = request.autoUpdate
        config.schedule = parseSchedule(request.schedule)

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

    fun findConfigById(id: UUID): Optional<GithubConfig> = configRepository.findById(id)

    private fun parseSchedule(schedule: UpdateSchedule): String {
        return StringBuilder()
            .append(schedule.seconds + " ")
            .append(schedule.minutes + " ")
            .append(schedule.hour + " ")
            .append(schedule.dayOfMonth + " ")
            .append(schedule.monthOfYear + " ")
            .append(schedule.dayOfWeek)
            .toString()
    }
}
