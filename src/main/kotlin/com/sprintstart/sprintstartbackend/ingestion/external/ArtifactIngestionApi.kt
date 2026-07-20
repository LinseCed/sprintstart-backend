package com.sprintstart.sprintstartbackend.ingestion.external

import java.time.Instant
import java.util.UUID

/**
 * Exported ingestion-module API for other backend modules.
 *
 * Exposes read-only ingestion metadata about a component without leaking the ingestion module's
 * internal entities. Other modules should depend on this interface instead of querying the
 * ingestion repositories directly.
 */
interface ArtifactIngestionApi {
    /**
     * Returns when a component (`owner/repo`) was first ingested, or null when it has no ingested
     * artifacts.
     */
    fun getFirstIngestedAt(component: String): Instant?

    /**
     * Batch variant of [getFirstIngestedAt]. Only components with a known timestamp are present in
     * the returned map.
     */
    fun getFirstIngestedAt(components: Collection<String>): Map<String, Instant>

    /**
     * Returns whether an ingested artifact with [artifactId] exists.
     */
    fun exists(artifactId: UUID): Boolean

    /**
     * Returns the content hash of an ingested artifact, or null if it has none on record.
     *
     * Callers that need to distinguish "no such artifact" from "artifact has no hash" should check
     * [exists] first.
     */
    fun getHash(artifactId: UUID): String?

    /**
     * Returns whether the artifact exists and belongs to the specified project.
     */
    fun existsInProject(projectId: UUID, artifactId: UUID): Boolean

    /**
     * Summarizes what one GitHub account has authored inside a project's ingested artifacts.
     *
     * Reads only the corpus the project already has connected -- no GitHub call -- so it can
     * describe a person's prior involvement without any new data source. Only issues and pull
     * requests carry an author, so commits and files never contribute (see `Artifact.authorLogin`).
     *
     * @param projectId The project whose ingested artifacts to look at.
     * @param authorLogin Lower-cased GitHub login to attribute artifacts to.
     * @return One entry per artifact authored by that account; empty when there are none.
     */
    fun getAuthoredWork(projectId: UUID, authorLogin: String): List<AuthoredArtifact>

    /**
     * The pull requests one GitHub account has authored in a project, with the timestamps
     * onboarding measures against.
     *
     * Separate from [getAuthoredWork] because it answers a different question with a different
     * shape: that one describes involvement (where, what kind), this one describes a lifecycle
     * (opened, answered, merged). Folding the timestamps into `AuthoredArtifact` would put a
     * pull-request-only concern on a type that also covers issues.
     *
     * Reads only artifacts already ingested -- no GitHub call.
     */
    fun getAuthoredPullRequests(projectId: UUID, authorLogin: String): List<AuthoredPullRequest>

    /**
     * The ingested artifact one starter-work task was mined from, by its source id.
     *
     * Exists so task-scoped orientation can be assembled from what the issue *actually says* — its
     * body and its labels — rather than from the one-line summary the mining pass wrote. Reads only
     * artifacts already ingested; no GitHub call.
     *
     * @param sourceId The backend's stable identifier, e.g. `github:org/repo:ISSUE:123`.
     * @return The artifact's own text, or null when nothing with that source id is ingested.
     */
    fun getTaskSource(sourceId: String): TaskSourceArtifact?
}

/**
 * The text of the artifact a task came from.
 *
 * Carries body and labels, unlike [AuthoredArtifact], because here the content *is* the point:
 * orientation is aimed at what this task involves, so the retrieval it drives has to see the task's
 * own words.
 */
data class TaskSourceArtifact(
    val title: String?,
    val body: String?,
    val labels: List<String>,
    val sourceUrl: String?,
)

/**
 * One pull request a person authored, reduced to its lifecycle.
 *
 * [firstResponseAt] is the earliest reaction from anyone else -- a review or a comment. A null
 * means nobody has responded yet, which is a finding rather than missing data: an unanswered pull
 * request is the failure onboarding instrumentation exists to catch.
 */
data class AuthoredPullRequest(
    val artifactId: UUID,
    val openedAt: Instant?,
    val firstResponseAt: Instant?,
    val mergedAt: Instant?,
    val state: String?,
)

/**
 * One artifact a person authored, reduced to what a prior can be built from.
 *
 * Deliberately carries no title or body: the point is *that* somebody has worked here and on what
 * kind of thing, not the content of their work.
 */
data class AuthoredArtifact(
    val artifactType: String,
    val repositoryFullName: String?,
    val labels: List<String>,
)
