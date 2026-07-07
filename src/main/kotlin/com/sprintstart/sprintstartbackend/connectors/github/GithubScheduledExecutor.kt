package com.sprintstart.sprintstartbackend.connectors.github

import com.sprintstart.sprintstartbackend.connectors.github.service.GithubConfigService
import com.sprintstart.sprintstartbackend.connectors.github.service.GithubConnectorService
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
    private val githubConnectionService: GithubConnectorService,
) {
    @Scheduled(fixedRate = 60_000)
    fun tick() {
        val now = Instant.now()
        val repositoriesToUpdate = githubConfigService.findAllRepositoriesDueForSync(now)

        repositoriesToUpdate.forEach { repository ->
            
        }
    }
}
