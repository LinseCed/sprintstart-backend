package com.sprintstart.sprintstartbackend.connectors.github.service

import com.sprintstart.sprintstartbackend.connectors.ConnectionState
import com.sprintstart.sprintstartbackend.connectors.github.GithubClient
import com.sprintstart.sprintstartbackend.connectors.github.external.GithubRepositoryApi
import com.sprintstart.sprintstartbackend.connectors.github.external.GithubSourceInstanceDto
import com.sprintstart.sprintstartbackend.connectors.github.external.dto.PullRequestEvidence
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.PullRequest
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
            authorLogin = author?.login?.lowercase(),
        )

    override fun getRepositoryIdByOwnerAndName(owner: String, name: String): UUID? =
        githubRepositoryConnectionRepository.findByOwnerAndName(owner, name)?.id

    override fun getRepositoryIdsByProject(projectId: UUID): List<UUID> =
        githubRepositoryConnectionRepository.findAllByProjectId(projectId).map { it.id }

    @Transactional(readOnly = true)
    override fun getSourceInstances(projectId: UUID?): List<GithubSourceInstanceDto> {
        val connections =
            if (projectId != null) {
                githubRepositoryConnectionRepository.findAllByProjectId(projectId)
            } else {
                githubRepositoryConnectionRepository.findAll()
            }

        return connections
            .sortedWith(compareBy({ it.owner }, { it.name }))
            .map { it.toSourceInstanceDto() }
    }

    @Transactional
    override fun removeProjectFromAllRepositories(projectId: UUID) {
        val connections = githubRepositoryConnectionRepository.findAllByProjectId(projectId)
        connections.forEach { it.projectIdsInternal.remove(projectId) }
        githubRepositoryConnectionRepository.saveAll(connections)
    }

    private fun GithubRepositoryConnection.toSourceInstanceDto(): GithubSourceInstanceDto {
        val snapshot = snapshot
        return GithubSourceInstanceDto(
            repositoryId = id,
            owner = owner,
            name = name,
            status = toSourceStatus(),
            enabled = sourceEnabled,
            lastCommitsSyncAt = snapshot?.lastCommitsSyncAt,
            lastIssuesSyncAt = snapshot?.lastIssuesSyncAt,
            lastPullRequestsSyncAt = snapshot?.lastPullRequestsSyncAt,
        )
    }
}

/**
 * Maps a GitHub repository connection to the stable source-status vocabulary shared by the
 * connector overview and the ingestion status APIs.
 *
 * A disabled source always reports `DISABLED`, regardless of its underlying connection state, so a
 * paused repository is not shown as actively connected. Kept in the GitHub module as the single
 * owner of this mapping.
 */
internal fun GithubRepositoryConnection.toSourceStatus(): String {
    if (!sourceEnabled) {
        return "DISABLED"
    }

    return when (connectionState) {
        ConnectionState.UP_TO_DATE -> "CONNECTED"
        ConnectionState.UPDATING -> "UPDATING"
        ConnectionState.OUT_OF_DATE -> "OUT_OF_DATE"
        ConnectionState.FAILED -> "FAILED"
    }
}
