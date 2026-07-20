package com.sprintstart.sprintstartbackend.ingestion.repository

import com.sprintstart.sprintstartbackend.ingestion.model.entity.Artifact
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactAiSyncState
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Persistence access for stored artifacts and project-scoped artifact searches.
 */
interface ArtifactRepository : JpaRepository<Artifact, UUID> {
    fun findBySourceId(sourceId: String): Artifact?

    fun findAllByIngestionRunId(runId: UUID): MutableList<Artifact>

    /**
     * Returns the next artifacts owed to the AI index, oldest first.
     *
     * Drives the incremental AI sync drainer: artifacts become `PENDING` as soon as they are
     * created or changed, so this deliberately spans runs -- a crawl that is still fetching has
     * pending artifacts worth embedding right now, and an artifact updated by a later run is owed
     * again even though its `ingestionRun` still points at the run that first created it.
     *
     * @param now Cut-off for the retry backoff; artifacts with no `aiSyncNextAttemptAt` are always
     * eligible.
     * @param pageable Caps the batch size (page 0 only -- drained rows leave the result set).
     */
    @Query(
        """
            SELECT a
            FROM Artifact a
            WHERE a.aiSyncState = com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactAiSyncState.PENDING
                AND (a.aiSyncNextAttemptAt IS NULL OR a.aiSyncNextAttemptAt <= :now)
            ORDER BY a.aiSyncNextAttemptAt ASC NULLS FIRST, a.ingestedAt ASC
        """,
    )
    fun findPendingAiSync(@Param("now") now: Instant, pageable: Pageable): List<Artifact>

    /**
     * Returns the artifacts a given GitHub account authored within one project.
     *
     * The basis for recognizing a hire's own prior work: with their declared `User.githubLogin`,
     * their issues and pull requests in the project's already-connected repositories can be found
     * without asking GitHub for anything new. Only issues and pull requests carry an author login
     * (see `Artifact.authorLogin`), so commits and files never match.
     */
    @Query(
        """
            SELECT DISTINCT a
            FROM Artifact a
            JOIN a.projectIdsInternal p
            WHERE p = :projectId
                AND a.authorLogin = :authorLogin
        """,
    )
    fun findAllByProjectIdAndAuthorLogin(
        @Param("projectId") projectId: UUID,
        @Param("authorLogin") authorLogin: String,
    ): List<Artifact>

    fun countByAiSyncRunIdAndAiSyncState(runId: UUID, state: ArtifactAiSyncState): Long

    fun findAllByAiSyncRunIdAndAiSyncState(runId: UUID, state: ArtifactAiSyncState): List<Artifact>

    /**
     * Returns one artifact page limited to artifacts linked to the given project.
     */
    @Query(
        """
            SELECT DISTINCT a
            FROM Artifact a
            JOIN a.projectIdsInternal p
            WHERE p = :projectId
        """,
    )
    fun findAllByProjectId(@Param("projectId") projectId: UUID, pageable: Pageable): Page<Artifact>

    /**
     * Returns one filtered artifact page limited to artifacts linked to the given project.
     */
    @Query(
        """
            SELECT DISTINCT a
            FROM Artifact a
            JOIN a.projectIdsInternal p
            WHERE p = :projectId
                AND (LOWER(a.title) LIKE LOWER(CONCAT('%', :filter, '%'))
                OR LOWER(a.artifactType) LIKE LOWER(CONCAT('%', :filter, '%'))
                OR LOWER(a.sourceSystem) LIKE LOWER(CONCAT('%', :filter, '%'))
                OR LOWER(a.metadata) LIKE LOWER(CONCAT('%', :filter, '%')))
        """,
    )
    fun searchByProjectId(
        @Param(
            "projectId",
        ) projectId: UUID,
        @Param("filter") filter: String, pageable: Pageable,
    ): Page<Artifact>

    fun deleteBySourceId(sourceId: String)

    /**
     * Returns one filtered artifact page across all projects.
     */
    @Query(
        """
            SELECT a
            FROM Artifact a
            WHERE LOWER(a.title) LIKE LOWER(CONCAT('%', :filter, '%'))
                OR LOWER(a.artifactType) LIKE LOWER(CONCAT('%', :filter, '%'))
                OR LOWER(a.sourceSystem) LIKE LOWER(CONCAT('%', :filter, '%'))
                OR LOWER(a.metadata) LIKE LOWER(CONCAT('%', :filter, '%'))
        """,
    )
    fun search(
        @Param("filter") filter: String, pageable: Pageable,
    ): Page<Artifact>

    /**
     * Returns the earliest ingestion timestamp across all artifacts of a GitHub component.
     *
     * The component is an `owner/repo` string; artifact source ids have the form
     * `github:owner/repo:TYPE:...`, so they are matched by prefix. Because existing artifacts are
     * updated in place on re-ingestion (their `ingestedAt` is immutable), the minimum is the time
     * the component was first ingested. Returns null when the component has no ingested artifacts.
     */
    @Query(
        "SELECT MIN(a.ingestedAt) FROM Artifact a WHERE a.sourceId LIKE CONCAT('github:', :component, ':%')",
    )
    fun findFirstIngestedAt(
        @Param("component") component: String,
    ): Instant?
}
