package com.sprintstart.sprintstartbackend.ingestion.model.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/**
 * Persisted as a bare JSON object with no type tag (see [ArtifactMetadataJsonMapper]), so the
 * concrete subtype has to be recovered on read from the fields that are present. DEDUCTION does
 * exactly that — it writes no discriminator (existing rows and new writes stay identical) and
 * infers the subtype from its distinct field set. The subtypes are disjoint
 * (`repositoryId`/`repositoryFullName` vs `storagePath`/`actorId` vs `issueKey`/`statusCategory`),
 * so deduction is unambiguous.
 *
 * Without this, `objectMapper.readValue(json, ArtifactMetadata::class.java)` cannot construct the
 * abstract interface and throws — which is what stalled the buddy's `get_suggested_tasks` tool.
 *
 * ⚠️ **[JiraArtifactMetadataWrapper] was written by ingestion and never registered here**, so
 * reading a Jira issue's metadata back had never worked: deduction saw two candidates, matched
 * neither, and threw. It went unnoticed because every existing reader reaches artifacts through
 * `authorLogin`, which is GitHub-only and therefore never selects a Jira row. Role tracks P4 is the
 * first thing to read one.
 *
 * The other Jira DTOs — project, issue type, comment, history — implement this interface too but
 * are deliberately *not* registered: they are fields inside the wrapper, deserialized as their own
 * concrete types, and adding them as candidates would only give deduction more ways to be
 * ambiguous.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
    JsonSubTypes.Type(GithubArtifactMetadata::class),
    JsonSubTypes.Type(UploadArtifactMetadata::class),
    JsonSubTypes.Type(JiraArtifactMetadataWrapper::class),
)
sealed interface ArtifactMetadata

data class GithubArtifactMetadata(
    val repositoryId: UUID,
    val repositoryFullName: String,
) : ArtifactMetadata

/**
 * `actorId` is operation-neutral: it is the uploader for stored artifact metadata and the remover
 * for failed deletion metadata.
 */
data class UploadArtifactMetadata(
    var storagePath: String? = null,
    var actorId: UUID,
) : ArtifactMetadata
