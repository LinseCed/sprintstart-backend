package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection
import com.sprintstart.sprintstartbackend.connectors.github.repository.GithubRepositoryConnectionRepository
import com.sprintstart.sprintstartbackend.connectors.github.service.toSourceStatus
import com.sprintstart.sprintstartbackend.ingestion.external.model.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.dto.response.SourceInstanceIngestionStatusResponse
import com.sprintstart.sprintstartbackend.ingestion.repository.IngestionRunRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Builds the per-connected-source-instance ingestion status view.
 *
 * Where [IngestionStatusService] collapses everything into one aggregate row per source system,
 * this service returns one row per connected GitHub repository: it takes the repository's current
 * connection status and snapshot timestamps and attaches the counters of that repository's latest
 * ingestion run.
 */
@Service
class IngestionSourceStatusService(
    private val githubRepositoryConnectionRepository: GithubRepositoryConnectionRepository,
    private val ingestionRunRepository: IngestionRunRepository,
) {
    /**
     * Returns one status row per connected GitHub repository.
     *
     * @param projectId When provided, only repositories connected to that project are returned;
     * otherwise all connected repositories are returned.
     * @return Per-source-instance status rows, ordered by owner and name for stable rendering.
     */
    @Transactional(readOnly = true)
    @Tracked("Retrieving ingestion status per source instance")
    fun getStatusPerSourceInstance(projectId: UUID? = null): List<SourceInstanceIngestionStatusResponse> {
        val connections =
            if (projectId != null) {
                githubRepositoryConnectionRepository.findAllByProjectId(projectId)
            } else {
                githubRepositoryConnectionRepository.findAll()
            }

        return connections
            .sortedWith(compareBy({ it.owner }, { it.name }))
            .map { it.toStatusResponse() }
    }

    private fun GithubRepositoryConnection.toStatusResponse(): SourceInstanceIngestionStatusResponse {
        val lastRun = ingestionRunRepository.findFirstByRepositoryIdOrderByStartedAtDesc(id)
        val snapshot = snapshot
        return SourceInstanceIngestionStatusResponse(
            sourceSystem = SourceSystem.GITHUB,
            sourceId = "$owner/$name",
            repositoryId = id,
            owner = owner,
            name = name,
            sourceUrl = "https://github.com/$owner/$name",
            status = toSourceStatus(),
            enabled = sourceEnabled,
            lastRunTime = lastRun?.startedAt,
            ingestedCount = lastRun?.ingestedCount ?: 0,
            updatedCount = lastRun?.updatedCount ?: 0,
            deletedCount = lastRun?.deletedCount ?: 0,
            failedCount = lastRun?.failedCount ?: 0,
            failedItems = lastRun?.failedItems ?: emptyList(),
            lastCommitsSyncAt = snapshot?.lastCommitsSyncAt,
            lastIssuesSyncAt = snapshot?.lastIssuesSyncAt,
            lastPullRequestsSyncAt = snapshot?.lastPullRequestsSyncAt,
        )
    }
}
