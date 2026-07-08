package com.sprintstart.sprintstartbackend.connectors.github.service

import com.sprintstart.sprintstartbackend.connectors.github.external.events.GithubRepositoryResourcesFetchingStartedEvent
import com.sprintstart.sprintstartbackend.connectors.github.external.events.update.GithubAllRepositoriesUpdateStartedEvent
import com.sprintstart.sprintstartbackend.connectors.github.external.events.update.GithubRepositoryUpdateFailedEvent
import com.sprintstart.sprintstartbackend.connectors.github.external.events.update.GithubRepositoryUpdateStartedEvent
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.api.requests.UpdateRepositoryRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.api.responses.UpdateAllRepositoriesResponse
import com.sprintstart.sprintstartbackend.connectors.github.models.api.responses.UpdateRepositoryResponse
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryNotConnectedException
import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.RepositoryNotInitializedException
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositorySnapshotRepository
import com.sprintstart.sprintstartbackend.connectors.github.service.internal.GithubCommitsService
import com.sprintstart.sprintstartbackend.connectors.github.service.internal.GithubFileService
import com.sprintstart.sprintstartbackend.connectors.github.service.internal.GithubIssuesService
import com.sprintstart.sprintstartbackend.connectors.github.service.internal.GithubPullRequestsService
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*

@Service
class GithubUpdatesService(
    private val eventPublisher: ApplicationEventPublisher,
    private val repoConnectionRepository: GithubRepositoryConnectionRepository,
    private val repoSnapshotRepository: GithubRepositorySnapshotRepository,
    private val applicationScope: CoroutineScope,
    private val fileService: GithubFileService,
    private val commitsService: GithubCommitsService,
    private val issuesService: GithubIssuesService,
    private val pullRequestsService: GithubPullRequestsService,
) {
    /**
     * Updates all connected GitHub repositories by synchronizing their latest state.
     *
     * This method iterates through all repositories stored in the database and triggers
     * an update for each, ensuring that related resources (files, commits, issues, pull requests)
     * are synchronized. A unique transaction ID is generated to track the overall update process.
     *
     * @return A UUID representing the transaction ID assigned to this update operation.
     */
    @Tracked("Updating all GitHub repositories")
    fun updateAllRepositories(): UpdateAllRepositoriesResponse {
        val transactionId = UUID.randomUUID()
        val allRepositories = repoConnectionRepository.findAll()

        eventPublisher.publishEvent(GithubAllRepositoriesUpdateStartedEvent(transactionId))

        allRepositories.forEach { repo ->
            updateRepository(repo, transactionId, true)
        }

        return UpdateAllRepositoriesResponse(transactionId)
    }

    /**
     * Updates the state of a specific connected GitHub repository.
     *
     * This method is responsible for synchronizing all resources of the GitHub repository
     * (e.g., files, commits, issues, pull requests) to their latest state. A unique transaction
     * ID is generated to track the update operation.
     *
     * @param request The request containing the details of the repository to update, including the owner and name.
     * @return A UUID representing the transaction ID assigned to this update operation.
     * @throws RepositoryNotConnectedException If the repository specified in the request is not connected.
     */
    @Tracked("Updating GitHub repository")
    fun updateRepository(request: UpdateRepositoryRequest, performUpdate: Boolean): UpdateRepositoryResponse {
        val transactionId = UUID.randomUUID()

        eventPublisher.publishEvent(GithubRepositoryUpdateStartedEvent(transactionId, request.owner, request.name))

        val repository = runCatching {
            repoConnectionRepository.findByOwnerAndName(request.owner, request.name)
                ?: throw RepositoryNotConnectedException(request.owner, request.name)
        }.onFailure { e ->
            eventPublisher.publishEvent(GithubRepositoryUpdateFailedEvent(transactionId, request.owner, request.name))
            throw e
        }.getOrNull() ?: return UpdateRepositoryResponse(transactionId)

        updateRepository(repository, transactionId, performUpdate)

        return UpdateRepositoryResponse(transactionId)
    }

    /**
     * Updates the repository by fetching and processing the latest snapshot, commits, issues,
     * pull requests, and saving them in the repository.
     *
     * @param githubRepository The connection object for the GitHub repository to be updated.
     * @param transactionId The unique identifier for the transaction to track the update process.
     */
    private fun updateRepository(
        githubRepository: GithubRepositoryConnection,
        transactionId: UUID,
        performUpdate: Boolean,
    ) {
        eventPublisher.publishEvent(
            GithubRepositoryUpdateStartedEvent(
                transactionId,
                githubRepository.owner,
                githubRepository.name,
            ),
        )

        val latestSnapshot = runCatching {
            repoSnapshotRepository.findLatestByRepository(githubRepository.id)
                ?: throw RepositoryNotInitializedException(githubRepository.owner, githubRepository.name)
        }.onFailure { e ->
            eventPublisher.publishEvent(
                GithubRepositoryUpdateFailedEvent(
                    transactionId,
                    githubRepository.owner,
                    githubRepository.name,
                ),
            )
            throw e
        }.getOrNull() ?: return

        eventPublisher.publishEvent(
            GithubRepositoryResourcesFetchingStartedEvent(
                transactionId,
                githubRepository.owner,
                githubRepository.name,
            ),
        )

        applicationScope.launch {
            fileService.fetchAndIngestFileUpdatesIncremental(githubRepository, transactionId, performUpdate)
        }
        applicationScope.launch {
            commitsService.fetchAndIngestLatestCommitsIfNecessary(latestSnapshot, transactionId, performUpdate)
        }
        applicationScope.launch {
            issuesService.fetchAndIngestAllIssues(
                githubRepository.id,
                githubRepository.owner,
                githubRepository.name,
                transactionId,
                performUpdate,
                latestSnapshot.lastIssuesSyncAt,
            )
        }
        applicationScope.launch {
            pullRequestsService.fetchAndIngestAllPullRequests(
                githubRepository.id,
                githubRepository.owner,
                githubRepository.name,
                transactionId,
                performUpdate,
                latestSnapshot.lastPullRequestsSyncAt,
            )
        }

        // If we didn't update the sources, we don't update timestamps.
        // If we were to update timestamps nonetheless, on the next actual update all updates from now to then would be lost.
        if (performUpdate) {
            repoSnapshotRepository.updateSyncTimestamps(githubRepository.id, Instant.now())
        }
    }
}
