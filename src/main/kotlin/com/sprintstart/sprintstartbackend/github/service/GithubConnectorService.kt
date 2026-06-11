package com.sprintstart.sprintstartbackend.github.service

import com.sprintstart.sprintstartbackend.github.GithubClient
import com.sprintstart.sprintstartbackend.github.external.events.*
import com.sprintstart.sprintstartbackend.github.models.GithubFileSnapshot
import com.sprintstart.sprintstartbackend.github.models.GithubFileSnapshotSharedId
import com.sprintstart.sprintstartbackend.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.github.models.GithubRepositorySnapshot
import com.sprintstart.sprintstartbackend.github.models.client.FileResponse
import com.sprintstart.sprintstartbackend.github.models.client.TreeEntry
import com.sprintstart.sprintstartbackend.github.repository.GithubFileSnapshotRepository
import com.sprintstart.sprintstartbackend.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.github.repository.GithubRepositorySnapshotRepository
import jakarta.transaction.Transactional
import kotlinx.coroutines.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.*

/**
 * Define a Base64 decoder.
 *
 * Used for decoding file contents from GitHub api, as these arrive
 * encoded.
 */
private val DECODER: Base64.Decoder = Base64.getMimeDecoder()

/**
 * Handles the business logic of connecting and managing GitHub repositories.
 */
@Service
class GithubConnectorService(
    private val repoConnectionRepository: GithubRepositoryConnectionRepository,
    private val repoSnapshotRepository: GithubRepositorySnapshotRepository,
    private val fileSnapshotRepository: GithubFileSnapshotRepository,
    private val applicationScope: CoroutineScope,
    private val githubClient: GithubClient,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher,
) {
    /**
     * Connect a new repository.
     *
     * Given a `owner` and a `name` of a GitHub repository, this connects the repository
     * to the SprintStart application and starts all processing jobs in the background.
     * Tasks started for background execution include:
     *
     * * Fetching the repository code
     * * Fetching the repository commits
     * * Fetching the repository issues
     * * Fetching the repository pull requests
     * * Starting a CRON job that checks for upates every night.
     *
     * _**Schema:** `https://github.com/{owner}/{name}`_
     *
     * @param owner The name of the GitHub user/org that owns the repository to connect.
     * @param name The name of the repository to connect.
     *
     * @throws IllegalStateException If on one of the processed file resources, the GitHub api
     * returns malformed responses.
     */
    @Transactional
    fun connectRepository(owner: String, name: String) {
        // Save an initial snapshot of the repository
        val transactionId = UUID.randomUUID()
        val repoConnection = GithubRepositoryConnection(
            owner = owner,
            name = name,
        )
        val repoSnapshot = GithubRepositorySnapshot(
            repository = repoConnection,
        )

        repoConnection.snapshot = repoSnapshot
        repoConnectionRepository.save(repoConnection)
        repoSnapshotRepository.save(repoSnapshot)

        // Launch data collectors/processors
        applicationScope.launch { fetchAndIngestAllFiles(repoConnection, transactionId) }
        applicationScope.launch { fetchAndIngestAllCommits(repoConnection, transactionId) }
        applicationScope.launch { fetchAndIngestAllIssues(repoConnection, transactionId) }
        applicationScope.launch { fetchAndIngestAllPullRequests(repoConnection, transactionId) }
    }

    suspend fun checkAllRepositoriesForUpdates() {
        val githubRepositories = repoConnectionRepository.findAll()
        githubRepositories
            .chunked(10)
            .forEach { batch ->
                coroutineScope {
                    batch
                        .map {
                            async { checkGithubRepositoryForUpdates(it) }
                        }.awaitAll()
                }
            }
    }

    fun updateFiles() {}

    fun updateCommits() {}

    fun updateIssues() {}

    fun updatePullRequests() {}

    private fun checkGithubRepositoryForUpdates(githubRepository: GithubRepositoryConnection) {
        // TODO: Check files for updates
        // TODO: Check commits for updates
        // TODO: Check issues for updates
        // TODO: Check pull requests for updates
    }

    /**
     * Given the information of a GitHub repository, finds, fetches and ingests all file resources.
     *
     * This function, given the information for identifying the GitHub repository,
     * first fetches a lightweight file tree from GitHub, and then instructs fetch and ingest actions
     * for each file resource in batches of 10 at a time, after applying pre-filters on filtered
     * file types like binaries.
     *
     * @param githubRepository The GitHub repository (as handled internally) this file resource belongs to.
     * @param transactionId The UUID of the overall transaction, this fetch/ingest is a part of.
     *
     * @throws IllegalStateException If on one of the processed file resources, the GitHub api
     * returns malformed responses.
     */
    private suspend fun fetchAndIngestAllFiles(
        githubRepository: GithubRepositoryConnection,
        transactionId: UUID,
    ) {
        val fileTree = githubClient.fetchFileTree(githubRepository.owner, githubRepository.name)

        // Compute files in batches of 10, to improve performance while not overloading the connection pool.
        // Fine-tune later if needed
        fileTree
            .tree
            .filter { it.type == "blob" }
            // TODO: Filter binaries etc...
            .chunked(10)
            .forEach { batch ->
                coroutineScope {
                    batch
                        .map { file ->
                            async { fetchAndIngestFilePreFilter(githubRepository, file, transactionId) }
                        }.awaitAll()
                }
            }
    }

    /**
     * Decides, whether an incremental fetch/ingest is needed, or a fetch/ingest
     * completely from scratch.
     *
     * This function acts as a pre-filter for [fetchAndIngestFile] and [fetchAndIngestFileInc],
     * deciding when to act as an update, and when to act from scratch.
     *
     * @param githubRepository The GitHub repository (as handled internally) this file resource belongs to.
     * @param fileMeta Lightweight metadata to the file to be fetched/ingested.
     * @param transactionId The UUID of the overall transaction, this fetch/ingest is a part of.
     *
     * @throws IllegalStateException If the fetched resource from GitHub does not pass validity checks.
     */
    private suspend fun fetchAndIngestFilePreFilter(
        githubRepository: GithubRepositoryConnection,
        fileMeta: TreeEntry,
        transactionId: UUID,
    ) {
        val fileSnapshot = fileSnapshotRepository.findByCombinedId(githubRepository.id, fileMeta.path)
        if (fileSnapshot == null) {
            fetchAndIngestFile(githubRepository, fileMeta, transactionId)
        } else {
            fetchAndIngestFileInc(githubRepository, fileSnapshot, transactionId)
        }
    }

    /**
     * Fetches a file resource from the GitHub REST api and marks it for ingestion to AI system.
     *
     * _Intended for use on **old** file resources, therefore just **updates** an existing file resource._
     *
     * This function fetches a given file resource from the GitHub REST api,
     * decodes the content, handles business logic with it (e.g. storing internal metadata),
     * and afterward marks that file resource for ingestion by publishing an according event
     * for the upload/ingestion module to pick up.
     *
     * @see GithubFileSnapshot
     *
     * @param githubRepository The GitHub repository (as handled internally) this file resource belongs to.
     * @param fileSnapshot The old snapshot of the file resource to be updated.
     * @param transactionId The UUID of the overall transaction, this fetch/ingest is a part of.
     *
     * @throws IllegalStateException if post-fetch validation fails.
     */
    private suspend fun fetchAndIngestFileInc(
        githubRepository: GithubRepositoryConnection,
        fileSnapshot: GithubFileSnapshot,
        transactionId: UUID,
    ) {
        val fileResponse = fetchFile(githubRepository.owner, githubRepository.name, fileSnapshot.id.path)
        if (fileResponse.sha == fileSnapshot.sha) {
            return // File hasn't changed - no need to sync
        }

        val decodedContent = DECODER.decode(fileResponse.content).toString(Charsets.UTF_8)

        fileSnapshotRepository.save(
            fileSnapshot.copy(
                lastIngestedAt = Instant.now(clock),
                sha = fileResponse.sha,
            ),
        )

        ingestFile(fileResponse.path, decodedContent, transactionId)
    }

    /**
     * Fetches a file resource from the GitHub REST api and marks it for ingestion to AI system.
     *
     * _Intended for use on **new** file resources._
     *
     * This function fetches a given file resource from the GitHub REST api,
     * decodes the content, handles business logic with it (e.g. storing internal metadata),
     * and afterward marks that file resource for ingestion by publishing an according event
     * for the upload/ingestion module to pick up.
     *
     * @see GithubRepositoryConnection
     * @see TreeEntry
     *
     * @param githubRepository The GitHub repository (as handled internally) this file resource belongs to.
     * @param fileMeta Lightweight metadata to the file to be fetched/ingested.
     * @param transactionId The UUID of the overall transaction, this fetch/ingest is a part of.
     *
     * @throws IllegalStateException if post-fetch validation fails.
     */
    private suspend fun fetchAndIngestFile(
        githubRepository: GithubRepositoryConnection,
        fileMeta: TreeEntry,
        transactionId: UUID,
    ) {
        val fileResponse = fetchFile(githubRepository.owner, githubRepository.name, fileMeta.path)
        val decodedContent = DECODER.decode(fileResponse.content).toString(Charsets.UTF_8)

        val fileSnapshotId = GithubFileSnapshotSharedId(
            repositoryId = githubRepository.id,
            path = fileResponse.path,
        )
        val fileSnapshot = GithubFileSnapshot(
            id = fileSnapshotId,
            sha = fileResponse.sha,
        )
        fileSnapshotRepository.save(fileSnapshot)

        ingestFile(fileResponse.path, decodedContent, transactionId)
    }

    /**
     * Fetches a file resource from the GitHub REST api.
     *
     * This function fetches a file resource from GitHub's REST api,
     * and validates and provides the result.
     *
     * @param owner The GitHub user/org that owns the repository to fetch from.
     * @param name The name of the repository to fetch from.
     * @param path The relative path of the file resource to fetch.
     *
     * @throws [IllegalStateException] if the resource provided by the GitHub api
     * does not correspond to the expected format regarding encoding and/or resource type.
     */
    private suspend fun fetchFile(owner: String, name: String, path: String): FileResponse {
        val file = githubClient.fetchFile(owner, name, path)

        check(file.type == "file") {
            "Received resource with type ${file.type} but expected type 'file'"
        }
        check(file.encoding == "base64") {
            "Received resource content encoded with ${file.encoding} but expected 'base64'"
        }

        return file
    }

    /**
     * Publishes a spring event to ingest the given resource into the AI system.
     *
     * This function publishes a [GithubFileFetchedEvent], that a handler in the upload/ingestion
     * module waits for, picks up, and then handles the ingestion of.
     *
     * @see GithubFileFetchedEvent
     *
     * @param path The relative path to the file to ingest.
     * @param content The actual content of the resource.
     * @param transactionId The UUID of the overall transaction, this fetch/ingest is a part of.
     */
    private fun ingestFile(path: String, content: String, transactionId: UUID) {
        val event = GithubFileFetchedEvent(
            transactionId = transactionId,
            path = path,
            content = content,
        )
        eventPublisher.publishEvent(event)
    }

    /**
     * Fetches and ingests **all** commits of a given GitHub repository.
     *
     * Given the `name` and `owner` of a GitHub repository, this function fetches all
     * commits of that repository and ingests them into the AI system.
     *
     * @see GithubCommitFetchedEvent
     *
     * @param githubRepository The GitHub repository (as handled internally) this resource belongs to.
     * @param transactionId The UUID of the overall transaction, this fetch/ingest is a part of.
     */
    private suspend fun fetchAndIngestAllCommits(githubRepository: GithubRepositoryConnection, transactionId: UUID) {
        val commits = githubClient.fetchAllCommits(githubRepository.owner, githubRepository.name)

        commits.forEach {
            val event = GithubCommitFetchedEvent(
                transactionId = transactionId,
                oid = it.oid,
                headline = it.messageHeadline,
                message = it.message,
                committedDate = it.committedDate,
                authorName = it.author?.name,
                authorEmail = it.author?.email,
                changedFilesIfAvailable = it.changedFilesIfAvailable,
                url = it.url,
            )
            eventPublisher.publishEvent(event)
        }
    }

    /**
     * Fetches and ingests **all** issues of a given GitHub repository.
     *
     * Given the `name` and `owner` of a GitHub repository, this function fetches all
     * issues of that repository and ingests them into the AI system.
     *
     * @see GithubIssueFetchedEvent
     *
     * @param githubRepository The GitHub repository (as handled internally) this resource belongs to.
     * @param transactionId The UUID of the overall transaction, this fetch/ingest is a part of.
     */
    private suspend fun fetchAndIngestAllIssues(githubRepository: GithubRepositoryConnection, transactionId: UUID) {
        val issues = githubClient.fetchAllIssues(githubRepository.owner, githubRepository.name)

        issues.forEach { issue ->
            val event = GithubIssueFetchedEvent(
                transactionId = transactionId,
                number = issue.number,
                title = issue.title,
                state = issue.state,
                createdAt = issue.createdAt,
                closedAt = issue.closedAt,
                url = issue.url,
                author = issue.author?.login,
                labels = issue.labels.nodes.map { it.name },
                assignees = issue.assignees.nodes.map { it.login },
                comments = issue.comments.nodes.map { GithubIssueComment(it.body, it.author?.login, it.createdAt) },
            )
            eventPublisher.publishEvent(event)
        }
    }

    /**
     * Fetches and ingests **all** pull requests of a given GitHub repository.
     *
     * Given the `name` and `owner` of a GitHub repository, this function fetches all
     * pull requests of that repository and ingests them into the AI system.
     *
     * @see GithubPullRequestFetchedEvent
     *
     * @param githubRepository The GitHub repository (as handled internally) this resource belongs to.
     * @param transactionId The UUID of the overall transaction, this fetch/ingest is a part of.
     */
    private suspend fun fetchAndIngestAllPullRequests(
        githubRepository: GithubRepositoryConnection,
        transactionId: UUID,
    ) {
        val pullRequests = githubClient.fetchAllPullRequests(githubRepository.owner, githubRepository.name)

        pullRequests.forEach { pullRequest ->
            val event = GithubPullRequestFetchedEvent(
                transactionId = transactionId,
                number = pullRequest.number,
                body = pullRequest.body,
                state = pullRequest.state,
                createdAt = pullRequest.createdAt,
                mergedAt = pullRequest.mergedAt,
                url = pullRequest.url,
                author = pullRequest.author?.login,
                labels = pullRequest.labels?.nodes?.map { it.name },
                reviews = pullRequest.reviews?.nodes?.map {
                    GithubPullRequestReview(
                        it.body,
                        it.state,
                        it.author?.login,
                    )
                },
                comments = pullRequest.comments?.nodes?.map {
                    GithubPullRequestComment(
                        it.body,
                        it.author?.login,
                        it.createdAt,
                    )
                },
                reviewThreads = pullRequest.reviewThreads?.nodes?.map { reviewThread ->
                    GithubPullRequestReviewThread(
                        reviewThread.comments.nodes.map {
                            GithubPullRequestReviewThreadComment(
                                it.body,
                                it.author?.login,
                                it.path,
                            )
                        },
                    )
                },
            )

            eventPublisher.publishEvent(event)
        }
    }
}
