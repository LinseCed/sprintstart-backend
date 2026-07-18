package com.sprintstart.sprintstartbackend.connectors.github.external

import com.sprintstart.sprintstartbackend.connectors.github.external.dto.PullRequestEvidence
import java.util.UUID

/**
 * Module-facing API for reading GitHub repository connection metadata needed outside the
 * GitHub module.
 */
interface GithubRepositoryApi {
    /**
     * Returns the project ids linked to one stored GitHub repository connection.
     *
     * @param id The internal repository connection identifier.
     * @return All SprintStart project ids currently linked to the repository connection.
     */
    fun getRepositoryProjectIdsById(id: UUID): Set<UUID>

    /**
     * Fetches one pull request's real, observed state on demand -- title, body, state, changed
     * files, CI status, commit messages -- for a repository connection this module already owns.
     *
     * @param repositoryConnectionId The internal repository connection identifier.
     * @param prNumber The pull request number to fetch.
     * @return The pull request's evidence, or null if it does not exist in that repository.
     * @throws NoSuchElementException When no repository connection exists for the given id.
     */
    suspend fun getPullRequestEvidence(repositoryConnectionId: UUID, prNumber: Int): PullRequestEvidence?
}
