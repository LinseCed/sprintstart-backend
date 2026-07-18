package com.sprintstart.sprintstartbackend.github.service

import com.sprintstart.sprintstartbackend.connectors.github.GithubClient
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubUser
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubUserPat
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.CommitMessage
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.PullRequest
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.PullRequestCommitNode
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.PullRequestCommitsConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.PullRequestFileNode
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.PullRequestFilesConnection
import com.sprintstart.sprintstartbackend.connectors.github.models.client.graphql.StatusCheckRollup
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.connectors.github.service.GithubRepositoryApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import java.util.UUID

class GithubRepositoryApiServiceTest {
    private val githubRepositoryConnectionRepository = mockk<GithubRepositoryConnectionRepository>()
    private val githubClient = mockk<GithubClient>()
    private val service = GithubRepositoryApiService(githubRepositoryConnectionRepository, githubClient)

    private val repositoryId = UUID.randomUUID()
    private val repository = GithubRepositoryConnection(
        id = repositoryId,
        owner = "owner",
        name = "repo",
        user = GithubUser(id = GithubUserPat("auth-id", "token-name"), token = "test-token"),
    )

    private fun pullRequest(
        statusCheckRollup: StatusCheckRollup? = StatusCheckRollup(state = "SUCCESS"),
        files: List<String> = listOf("src/Main.kt"),
        commitMessages: List<String> = listOf("fix: bug"),
    ) = PullRequest(
        number = 42,
        title = "Fix bug",
        body = "Closes #1",
        state = "MERGED",
        createdAt = "2024-01-01T00:00:00Z",
        mergedAt = "2024-01-02T00:00:00Z",
        url = "https://github.com/owner/repo/pull/42",
        author = null,
        labels = null,
        reviews = null,
        comments = null,
        reviewThreads = null,
        statusCheckRollup = statusCheckRollup,
        files = PullRequestFilesConnection(files.map { PullRequestFileNode(it) }),
        commits = PullRequestCommitsConnection(commitMessages.map { PullRequestCommitNode(CommitMessage(it)) }),
    )

    @Test
    fun `getPullRequestEvidence maps a found pull request to evidence`() = runTest {
        every { githubRepositoryConnectionRepository.findById(repositoryId) } returns Optional.of(repository)
        coEvery { githubClient.fetchPullRequest(repository, 42) } returns pullRequest()

        val result = service.getPullRequestEvidence(repositoryId, 42)

        assertThat(result).isNotNull()
        assertThat(result?.title).isEqualTo("Fix bug")
        assertThat(result?.body).isEqualTo("Closes #1")
        assertThat(result?.state).isEqualTo("MERGED")
        assertThat(result?.filesChanged).containsExactly("src/Main.kt")
        assertThat(result?.checksPassed).isTrue()
        assertThat(result?.commitMessages).containsExactly("fix: bug")
    }

    @Test
    fun `getPullRequestEvidence returns null when the PR does not exist`() = runTest {
        every { githubRepositoryConnectionRepository.findById(repositoryId) } returns Optional.of(repository)
        coEvery { githubClient.fetchPullRequest(repository, 99) } returns null

        val result = service.getPullRequestEvidence(repositoryId, 99)

        assertThat(result).isNull()
    }

    @Test
    fun `getPullRequestEvidence maps a failing status rollup to checksPassed false`() = runTest {
        every { githubRepositoryConnectionRepository.findById(repositoryId) } returns Optional.of(repository)
        coEvery { githubClient.fetchPullRequest(repository, 42) } returns
            pullRequest(statusCheckRollup = StatusCheckRollup(state = "FAILURE"))

        val result = service.getPullRequestEvidence(repositoryId, 42)

        assertThat(result?.checksPassed).isFalse()
    }

    @Test
    fun `getPullRequestEvidence maps a missing status rollup to checksPassed null`() = runTest {
        every { githubRepositoryConnectionRepository.findById(repositoryId) } returns Optional.of(repository)
        coEvery { githubClient.fetchPullRequest(repository, 42) } returns
            pullRequest(statusCheckRollup = null)

        val result = service.getPullRequestEvidence(repositoryId, 42)

        assertThat(result?.checksPassed).isNull()
    }

    @Test
    fun `getPullRequestEvidence throws when the repository connection does not exist`() {
        every { githubRepositoryConnectionRepository.findById(repositoryId) } returns Optional.empty()

        assertThrows<NoSuchElementException> {
            runBlocking { service.getPullRequestEvidence(repositoryId, 42) }
        }
    }
}
