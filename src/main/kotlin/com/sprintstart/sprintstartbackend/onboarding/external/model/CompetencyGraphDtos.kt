package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GenerateCompetencyGraphRequest(
    @SerialName("active_competencies") val activeCompetencies: List<ActiveCompetencySchema> = emptyList(),
    @SerialName("active_edges") val activeEdges: List<ActiveEdgeSchema> = emptyList(),
    @SerialName("last_fingerprint") val lastFingerprint: String? = null,
)

@Serializable
data class ActiveCompetencySchema(
    val key: String,
    val label: String,
    val description: String = "",
    val kind: String,
    @SerialName("repo_ref") val repoRef: String? = null,
)

@Serializable
data class ActiveEdgeSchema(
    @SerialName("from_key") val fromKey: String,
    @SerialName("to_key") val toKey: String,
    val kind: String = "PREREQUISITE",
)

@Serializable
data class GraphProposalOutcome(
    val status: String,
    val competencies: List<ProposedCompetencySchema> = emptyList(),
    val edges: List<ProposedEdgeSchema> = emptyList(),
    val provenance: GraphProvenanceSchema? = null,
    @SerialName("chunks_retrieved") val chunksRetrieved: Int = 0,
    val notes: List<String> = emptyList(),
)

@Serializable
data class ProposedCompetencySchema(
    val key: String,
    val label: String,
    val description: String = "",
    val kind: String,
    @SerialName("repo_ref") val repoRef: String? = null,
)

@Serializable
data class ProposedEdgeSchema(
    @SerialName("from_key") val fromKey: String,
    @SerialName("to_key") val toKey: String,
    val kind: String = "PREREQUISITE",
    val rationale: String = "",
)

@Serializable
data class GraphProvenanceSchema(
    @SerialName("corpus_fingerprint") val corpusFingerprint: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    val model: String? = null,
    val notes: List<String> = emptyList(),
)
