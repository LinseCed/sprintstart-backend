package com.sprintstart.sprintstartbackend.onboarding.external.model

import kotlinx.serialization.Serializable

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
