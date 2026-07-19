package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GenerateBlueprintsRequest(
    val scopes: List<String>? = null,
    val active: List<BlueprintSchema> = emptyList(),
    // The backend's live competency graph. The AI tags each generated step with the
    // best-matching key from this catalog (the blueprint->target bridge); the AI discards
    // any key not present here, so it is safe to send the whole graph.
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
    val blueprint: GeneratedBlueprint? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class GeneratedBlueprint(
    val scope: String,
    val version: String,
    val steps: List<GeneratedBlueprintStep> = emptyList(),
    val provenance: BlueprintProvenanceSchema? = null,
)

@Serializable
data class GeneratedBlueprintStep(
    val id: String,
    val title: String,
    val description: String? = null,
    @SerialName("min_experience") val minExperience: String? = null,
    val audience: List<String> = emptyList(),
    val requirement: String = "recommended",
    val invariant: Boolean = false,
    // The competency graph key the AI matched this step to, or null when none fit. Persisted onto
    // BlueprintStep.competencyKey (the blueprint->target bridge).
    @SerialName("competency_key") val competencyKey: String? = null,
)
