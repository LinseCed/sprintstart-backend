package com.sprintstart.sprintstartbackend.onboarding.model.response.assessment

/**
 * Whether the authenticated user has ever completed a skill-assessment interview.
 *
 * Drives the frontend's "needs assessment" gate: the source of truth is a COMPLETED
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.SkillAssessmentSession], not the
 * retired self-reported skill-wizard data.
 */
data class GetAssessmentStatusResponse(
    val completed: Boolean,
)
