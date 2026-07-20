package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A step-shaped baseline entry, as path generation still consumes it.
 *
 * **Transitional.** A baseline no longer stores steps -- it stores a competency selection
 * ([com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency]). These are
 * derived from that selection at call time so the AI's per-user path generation keeps working
 * unchanged; they are deleted along with the per-user step tree in backend#53.
 */
@Serializable
data class BlueprintStepSchema(
    val id: String,
    val title: String,
    val description: String = "",
    val requirement: String = "recommended",
    val audience: List<String> = emptyList(),
    @SerialName("min_experience")
    val minExperience: String? = null,
    val tags: List<String> = emptyList(),
    val invariant: Boolean = false,
    // Round-tripped so a re-generation preserves the key already assigned to an active step
    // (it survives the AI's invariant re-injection).
    @SerialName("competency_key")
    val competencyKey: String? = null,
)

@Serializable
data class BlueprintProvenanceSchema(
    @SerialName("corpus_fingerprint")
    val corpusFingerprint: String? = null,
    @SerialName("generated_at")
    val generatedAt: String? = null,
    val model: String? = null,
    val notes: List<String> = emptyList(),
)

/** The transitional step-shaped blueprint payload path generation consumes. See [BlueprintStepSchema]. */
@Serializable
data class BlueprintSchema(
    val scope: String,
    val version: String = "0",
    val source: String = "authored",
    val steps: List<BlueprintStepSchema> = emptyList(),
    val provenance: BlueprintProvenanceSchema? = null,
)

/**
 * A user's proficiency in one skill, mirroring the AI service's `SkillAssessmentSchema`.
 * Derived from the durable competency ledger (`UserCompetencyState` mapped to competency
 * labels); carrying the level (instead of a bare tag) lets proficiency drive AI
 * personalization.
 *
 * [level] is one of `beginner`, `intermediate`, `advanced`, `expert`
 * (case-insensitive; unknown values are handled gracefully by the AI service).
 */
@Serializable
data class SkillAssessmentSchema(
    val name: String,
    val level: String = "beginner",
)

@Serializable
data class GenerateOnboardingPathRequest(
    @SerialName("working_area")
    val workingArea: String,
    val skills: List<SkillAssessmentSchema> = emptyList(),
    val tags: List<String> = emptyList(),
    val blueprints: List<BlueprintSchema> = emptyList(),
)
