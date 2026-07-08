package com.sprintstart.sprintstartbackend.connectors.github

import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.UpdateRepositoryRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryConfigNotFoundException
import com.sprintstart.sprintstartbackend.connectors.github.service.GithubConfigService
import com.sprintstart.sprintstartbackend.connectors.github.service.GithubUpdatesService
import com.sprintstart.sprintstartbackend.shared.scheduler.ScheduledExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Registers and regularly runs jobs within a given schedule for the GitHub connector module.
 */
@Component
class GithubScheduledExecutor(
    private val scheduledExecutor: ScheduledExecutor,
    private val githubConfigService: GithubConfigService,
    private val githubUpdateService: GithubUpdatesService,
) {
    @Scheduled(fixedRate = 60_000)
    fun tick() {
        val now = Instant.now()
        val repositoriesToUpdate = githubConfigService.findAllRepositoriesDueForSync(now)

        repositoriesToUpdate.forEach { repository ->
            val repoConfig = githubConfigService
                .findConfigById(repository.id)
                .orElseThrow { RepositoryConfigNotFoundException(repository.owner, repository.name) }

            scheduledExecutor.launch(
                "Updating GitHub repository ${repository.owner}/${repository.name} (auto-update: ${repoConfig.autoUpdate})",
            ) {
                githubUpdateService.updateRepository(
                    UpdateRepositoryRequest(
                        repository.owner,
                        repository.name,
                    ),
                    repoConfig.autoUpdate,
                )
            }
        }
    }
}
