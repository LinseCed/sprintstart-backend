package com.sprintstart.sprintstartbackend.ingestion.model.dto.request

import com.sprintstart.sprintstartbackend.ingestion.external.model.SourceSystem
import com.sprintstart.sprintstartbackend.ingestion.model.entity.ArtifactType
import kotlinx.serialization.Serializable

@Serializable
data class ArtifactAiIngestRequest(
    val artifactId: String,
    val sourceSystem: SourceSystem,
    val sourceId: String,
    val sourceUrl: String?,
    val artifactType: ArtifactType,
    var title: String?,
    var bodyText: String?,
    val mime: String?,
    val language: String?,
    val state: String? = null,
    /**
     * Whether somebody at the source is already assigned to this issue, or **null when we cannot
     * tell**.
     *
     * Starter-work mining withholds an issue somebody else has taken -- but only on a definite
     * `true`. GitHub issues have assignees this system does not ingest, so theirs stay null, and
     * "unknown" must never be read as "free".
     */
    val hasAssignee: Boolean? = null,
    val labels: List<String> = emptyList(),
    /**
     * The projects this artifact belongs to, so the AI service can scope retrieval to one.
     *
     * Several is ordinary rather than exceptional: a repository shared between two projects is one
     * artifact serving both. Empty means unscoped, and the AI service keeps unscoped material
     * searchable from every project -- absent scope is not the same as excluded scope.
     */
    val projectIds: List<String> = emptyList(),
)
