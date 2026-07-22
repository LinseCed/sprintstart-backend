package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One competency selected into a baseline, on the AI wire.
 *
 * This is what a blueprint *is* now (see
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency]): the AI
 * proposes a selection over the competency graph, not prose steps.
 */
@Serializable
data class BaselineCompetencySchema(
    @SerialName("competency_key") val competencyKey: String,
    // Null means "use the competency's own bar" -- the AI only sets this when the scope genuinely
    // demands more (or less) than the graph's default.
    @SerialName("target_level") val targetLevel: Int? = null,
    val requirement: String = "recommended",
    val invariant: Boolean = false,
    // Why the proposer put this competency in the baseline. Shown to the reviewing PM; carries no
    // meaning for path projection.
    val rationale: String = "",
)

/**
 * A baseline as sent to and received from the AI service: scope, version, and the selection.
 */
@Serializable
data class BaselineSchema(
    val scope: String,
    val version: String = "0",
    val source: String = "authored",
    val competencies: List<BaselineCompetencySchema> = emptyList(),
    val provenance: AiProvenanceSchema? = null,
)

@Serializable
data class GenerateBlueprintsRequest(
    val scopes: List<String>? = null,
    val active: List<BaselineSchema> = emptyList(),
    // The backend's live competency graph -- the set the AI selects from. A key outside this
    // catalog is discarded on both sides, so it is safe to send the whole graph.
    @SerialName("active_competencies") val activeCompetencies: List<ActiveCompetencySchema> = emptyList(),
)

@Serializable
data class GenerateBlueprintsResponse(
    val outcomes: List<BlueprintOutcome> = emptyList(),
)

@Serializable
data class BlueprintOutcome(
    val scope: String,
    val status: String,
    val blueprint: BaselineSchema? = null,
    val notes: List<String> = emptyList(),
)
