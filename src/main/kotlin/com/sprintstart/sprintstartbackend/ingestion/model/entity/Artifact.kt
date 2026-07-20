package com.sprintstart.sprintstartbackend.ingestion.model.entity

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import java.time.Instant
import java.util.UUID

@Entity
class Artifact(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val sourceSystem: SourceSystem,
    @Column(nullable = false)
    val sourceId: String,
    @Column(length = 2048)
    val sourceUrl: String?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val artifactType: ArtifactType,
    var title: String?,
    @Column(columnDefinition = "TEXT")
    var content: String?,
    val mime: String?,
    val language: String?,
    // GitHub issue state (e.g. "OPEN"/"CLOSED"); null for non-issue artifact types. Refreshed
    // unconditionally on every re-fetch by GithubArtifactProviderService, independent of the
    // content hash, since a state change alone doesn't move title/body.
    var state: String? = null,
    @Column(nullable = false)
    val metadata: String = "{}",
    @ElementCollection
    @CollectionTable(
        name = "artifact_projects",
        joinColumns = [JoinColumn(name = "artifact_id")],
    )
    @Column(name = "project_id", nullable = false)
    // Add companion obj to Artifact to have Artifact.create
    // to keep internal state hidden
    private val projectIdsInternal: MutableSet<UUID> = mutableSetOf(),
    // GitHub issue labels (e.g. "good first issue"); empty for non-issue artifact types.
    // Replaced wholesale (clear + addAll) on re-fetch, not unioned -- a removed label must
    // disappear.
    @ElementCollection
    @CollectionTable(
        name = "artifact_labels",
        joinColumns = [JoinColumn(name = "artifact_id")],
    )
    @Column(name = "label", nullable = false)
    val labels: MutableList<String> = mutableListOf(),
    val createdAtSource: Instant?,
    val updatedAtSource: Instant?,
    @Column(nullable = false)
    val ingestedAt: Instant = Instant.now(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingestion_run_id")
    val ingestionRun: IngestionRun,
    @Column(name = "content_hash", length = 64)
    var hash: String?,
    // --- AI sync outbox (see ArtifactAiSyncState) ---------------------------------------------
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_sync_state", nullable = false)
    var aiSyncState: ArtifactAiSyncState = ArtifactAiSyncState.PENDING,
    // The run that last marked this artifact pending -- not `ingestionRun`, which stays pinned to
    // the run that first created the row. A run's AI-sync status is derived from this column, so
    // an artifact updated by a later run reports against that later run.
    @Column(name = "ai_sync_run_id")
    var aiSyncRunId: UUID? = null,
    @Column(name = "ai_sync_attempts", nullable = false)
    var aiSyncAttempts: Int = 0,
    // Earliest time the drainer may retry this artifact; null means "eligible now".
    @Column(name = "ai_sync_next_attempt_at")
    var aiSyncNextAttemptAt: Instant? = null,
    @Column(name = "ai_sync_error", columnDefinition = "TEXT")
    var aiSyncError: String? = null,
    @Column(name = "ai_synced_at")
    var aiSyncedAt: Instant? = null,
) {
    val projectIds: Set<UUID>
        get() = projectIdsInternal.toSet()

    /**
     * Marks this artifact as owed to the AI index again, attributed to [runId].
     *
     * Called on every change that alters what the AI service would embed (content, title, issue
     * state, labels). The attempt budget and backoff are reset because this is a *new* version of
     * the artifact, not a retry of the previous one.
     */
    fun markAiSyncPending(runId: UUID) {
        aiSyncState = ArtifactAiSyncState.PENDING
        aiSyncRunId = runId
        aiSyncAttempts = 0
        aiSyncNextAttemptAt = null
        aiSyncError = null
    }

    fun addProjectIds(projectIds: Set<UUID>) {
        projectIdsInternal.addAll(projectIds)
    }

    fun addProjectId(projectId: UUID) {
        projectIdsInternal.add(projectId)
    }
}
