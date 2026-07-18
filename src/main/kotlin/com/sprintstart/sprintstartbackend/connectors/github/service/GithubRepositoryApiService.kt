package com.sprintstart.sprintstartbackend.connectors.github.service

import com.sprintstart.sprintstartbackend.connectors.github.GithubClient
import com.sprintstart.sprintstartbackend.connectors.github.external.GithubRepositoryApi
import com.sprintstart.sprintstartbackend.connectors.github.external.dto.PullRequestEvidence
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.PullRequest
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Repository-backed implementation of the GitHub module API exposed to other modules.
 */
@Service
class GithubRepositoryApiService(
    private val githubRepositoryConnectionRepository: GithubRepositoryConnectionRepository,
    private val githubClient: GithubClient,
) : GithubRepositoryApi {
    /**
     * Resolves the project ids currently associated with one GitHub repository connection.
     *
     * @param id The internal repository connection identifier.
     * @return The set of linked SprintStart project ids.
     * @throws NoSuchElementException When no repository connection exists for the given id.
     */
    override fun getRepositoryProjectIdsById(id: UUID): Set<UUID> {
        val repo = githubRepositoryConnectionRepository.findById(id).orElseThrow {
            NoSuchElementException("Repository with id $id not found")
        }
        return repo.projectIds
    }

    override suspend fun getPullRequestEvidence(repositoryConnectionId: UUID, prNumber: Int): PullRequestEvidence? {
        val repo = githubRepositoryConnectionRepository.findById(repositoryConnectionId).orElseThrow {
            NoSuchElementException("Repository with id $repositoryConnectionId not found")
        }
        val pullRequest = githubClient.fetchPullRequest(repo, prNumber) ?: return null
        return pullRequest.toEvidence()
    }

    private fun PullRequest.toEvidence(): PullRequestEvidence =
        PullRequestEvidence(
            title = title,
            body = body ?: "",
            state = state,
            filesChanged = files?.nodes?.map { it.path } ?: emptyList(),
            checksPassed = when (statusCheckRollup?.state) {
                "SUCCESS" -> true
                "FAILURE", "ERROR" -> false
                else -> null
            },
            commitMessages = commits?.nodes?.map { it.commit.message } ?: emptyList(),
        )
}
