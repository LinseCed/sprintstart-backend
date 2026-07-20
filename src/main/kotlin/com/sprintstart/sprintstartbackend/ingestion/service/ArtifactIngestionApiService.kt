package com.sprintstart.sprintstartbackend.ingestion.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.ingestion.external.AuthoredArtifact
import com.sprintstart.sprintstartbackend.ingestion.external.AuthoredPullRequest
import com.sprintstart.sprintstartbackend.ingestion.external.RepositoryResponsiveness
import com.sprintstart.sprintstartbackend.ingestion.external.TaskSourceArtifact
import com.sprintstart.sprintstartbackend.ingestion.model.dto.GithubArtifactMetadata
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import com.sprintstart.sprintstartbackend.ingestion.model.mapper.ArtifactMetadataJsonMapper
import com.sprintstart.sprintstartbackend.ingestion.repository.ArtifactRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Service implementation of the ingestion metadata API used by other modules.
 *
 * A small read-only adapter over the artifact repository; it does not touch the ingestion write
 * path or expose internal ingestion entities.
 */
@Service
internal class ArtifactIngestionApiService(
    private val artifactRepository: ArtifactRepository,
    private val artifactMetadataJsonMapper: ArtifactMetadataJsonMapper,
) : ArtifactIngestionApi {
    @Transactional(readOnly = true)
    override fun getFirstIngestedAt(component: String): Instant? {
        return artifactRepository.findFirstIngestedAt(component)
    }

    @Transactional(readOnly = true)
    override fun getFirstIngestedAt(components: Collection<String>): Map<String, Instant> {
        return components
            .distinct()
            .mapNotNull { component ->
                artifactRepository.findFirstIngestedAt(component)?.let { component to it }
            }.toMap()
    }

    @Transactional(readOnly = true)
    override fun exists(artifactId: UUID): Boolean {
        return artifactRepository.existsById(artifactId)
    }

    @Transactional(readOnly = true)
    override fun existsInProject(projectId: UUID, artifactId: UUID): Boolean {
        return artifactRepository.findById(artifactId).map { it.projectIds.contains(projectId) }.orElse(false)
    }

    @Transactional(readOnly = true)
    override fun getHash(artifactId: UUID): String? {
        return artifactRepository.findById(artifactId).orElse(null)?.hash
    }

    @Transactional(readOnly = true)
    override fun getAuthoredPullRequests(projectId: UUID, authorLogin: String): List<AuthoredPullRequest> {
        return artifactRepository
            .findAllByProjectIdAndAuthorLogin(projectId, authorLogin.lowercase())
            .filter { it.artifactType == ArtifactType.PULL_REQUEST }
            .map {
                AuthoredPullRequest(
                    artifactId = it.id,
                    openedAt = it.createdAtSource,
                    firstResponseAt = it.firstResponseAtSource,
                    mergedAt = it.mergedAtSource,
                    state = it.state,
                )
            }
    }

    @Transactional(readOnly = true)
    override fun getAuthoredWork(projectId: UUID, authorLogin: String): List<AuthoredArtifact> {
        return artifactRepository
            .findAllByProjectIdAndAuthorLogin(projectId, authorLogin.lowercase())
            .map { artifact ->
                val metadata = artifactMetadataJsonMapper.fromJson(artifact.metadata)
                AuthoredArtifact(
                    artifactType = artifact.artifactType.name,
                    repositoryFullName = (metadata as? GithubArtifactMetadata)?.repositoryFullName,
                    labels = artifact.labels.toList(),
                )
            }
    }

    @Transactional(readOnly = true)
    override fun getRepositoryResponsiveness(projectId: UUID): List<RepositoryResponsiveness> {
        return artifactRepository
            .findAllByProjectIdAndArtifactType(projectId, ArtifactType.PULL_REQUEST)
            .groupBy {
                (artifactMetadataJsonMapper.fromJson(it.metadata) as? GithubArtifactMetadata)?.repositoryFullName
            }.mapNotNull { (repository, pullRequests) ->
                if (repository == null) return@mapNotNull null
                // A pull request that was merged without a recorded response still got attention,
                // so only one with neither is counted as unanswered.
                val latencies = pullRequests.mapNotNull { hoursBetween(it.createdAtSource, it.firstResponseAtSource) }
                val unanswered = pullRequests.count {
                    it.firstResponseAtSource == null && it.mergedAtSource == null && it.createdAtSource != null
                }
                RepositoryResponsiveness(
                    repositoryFullName = repository,
                    medianHoursToFirstResponse = median(latencies),
                    answeredCount = latencies.size,
                    unansweredCount = unanswered,
                )
            }
    }

    private fun hoursBetween(from: Instant?, to: Instant?): Long? {
        if (from == null || to == null) return null
        return Duration.between(from, to).toHours()
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    @Transactional(readOnly = true)
    override fun getTaskSource(sourceId: String): TaskSourceArtifact? {
        val artifact = artifactRepository.findBySourceId(sourceId) ?: return null
        return TaskSourceArtifact(
            title = artifact.title,
            body = artifact.content,
            labels = artifact.labels.toList(),
            sourceUrl = artifact.sourceUrl,
        )
    }
}
