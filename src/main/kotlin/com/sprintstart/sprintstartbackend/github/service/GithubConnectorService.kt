package com.sprintstart.sprintstartbackend.github.service

import com.sprintstart.sprintstartbackend.github.GithubClient
import com.sprintstart.sprintstartbackend.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.github.models.GithubRepositorySnapshot
import com.sprintstart.sprintstartbackend.github.repository.GithubFileSnapshotRepository
import com.sprintstart.sprintstartbackend.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.github.repository.GithubRepositorySnapshotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service

@Service
class GithubConnectorService(
    val repoConnectionRepository: GithubRepositoryConnectionRepository,
    val repoSnapshotRepository: GithubRepositorySnapshotRepository,
    val fileSnapshotRepository: GithubFileSnapshotRepository
    val applicationScope: CoroutineScope,
    val githubClient: GithubClient,
) {
    fun connectRepository(owner: String, name: String) {
        // Save a initial snapshot of the repository
        val repoConnection = GithubRepositoryConnection(
            owner = owner,
            name = name,
            snapshot = null,
        )
        val repoSnapshot = GithubRepositorySnapshot(
            repository = repoConnection,
        )
        repoConnection.snapshot = repoSnapshot

        repoConnectionRepository.save(repoConnection)
        repoSnapshotRepository.save(repoSnapshot)

        // Launch data collectors/processors
        applicationScope.launch {
            launch { fetchAndIngestAllCommits(owner, name) }
            launch { fetchAndIngestAllIssues(owner, name)}
        }
    }

    private suspend fun fetchAndIngestAllFiles(owner: String, name: String) {
        // TODO
    }

    private suspend fun fetchAndIngestAllCommits(owner: String, name: String) {
        val commits = githubClient.fetchAllCommits(owner, name)

        // TODO: Ingest
    }

    private suspend fun fetchAndIngestAllIssues(owner: String, name: String) {
        val issues = githubClient.fetchAllIssues(owner, name)

        // TODO: Ingest
    }

    private suspend fun fetchAndIngestAllPullRequests(owner: String, name: String) {
        // TODO
    }
}
