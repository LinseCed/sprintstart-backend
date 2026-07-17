package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SynthesizeLessonRequest(
    @SerialName("competency_key") val competencyKey: String,
    @SerialName("competency_label") val competencyLabel: String,
    @SerialName("competency_description") val competencyDescription: String = "",
    val level: String = "beginner",
    @SerialName("last_fingerprint") val lastFingerprint: String? = null,
)

@Serializable
data class LessonOutcome(
    val status: String,
    val lesson: LessonContentSchema? = null,
    val provenance: LessonProvenanceSchema? = null,
    @SerialName("chunks_retrieved") val chunksRetrieved: Int = 0,
    val notes: List<String> = emptyList(),
)

@Serializable
data class LessonContentSchema(
    @SerialName("competency_key") val competencyKey: String,
    val level: String,
    val title: String,
    val body: String,
    val citations: List<CitationRefSchema> = emptyList(),
)

@Serializable
data class CitationRefSchema(
    val filename: String,
    @SerialName("chunk_id") val chunkId: String,
    @SerialName("source_url") val sourceUrl: String? = null,
)

@Serializable
data class LessonProvenanceSchema(
    @SerialName("corpus_fingerprint") val corpusFingerprint: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    val model: String? = null,
    val notes: List<String> = emptyList(),
)
