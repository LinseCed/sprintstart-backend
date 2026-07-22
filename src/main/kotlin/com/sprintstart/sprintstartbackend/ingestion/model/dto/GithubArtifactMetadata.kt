package com.sprintstart.sprintstartbackend.ingestion.model.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.UUID

/**
 * Persisted as a bare JSON object with no type tag (see [ArtifactMetadataJsonMapper]), so the
 * concrete subtype has to be recovered on read from the fields that are present. DEDUCTION does
 * exactly that — it writes no discriminator (existing rows and new writes stay identical) and
 * infers the subtype from its distinct field set. The two subtypes are disjoint
 * (`repositoryId`/`repositoryFullName` vs `storagePath`/`actorId`), so deduction is unambiguous.
 *
 * Without this, `objectMapper.readValue(json, ArtifactMetadata::class.java)` cannot construct the
 * abstract interface and throws — which is what stalled the buddy's `get_suggested_tasks` tool.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes(
    JsonSubTypes.Type(GithubArtifactMetadata::class),
    JsonSubTypes.Type(UploadArtifactMetadata::class),
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
