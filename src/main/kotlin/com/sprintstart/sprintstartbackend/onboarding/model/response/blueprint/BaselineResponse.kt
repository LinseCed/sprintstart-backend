package com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint

/**
 * A project's live baseline: the competencies a hire is expected to reach, as the PM has authored
 * them directly. Entries reuse [BlueprintCompetencyResponse] so the direct-authoring surface and the
 * proposal-review surface render the same shape.
 */
data class BaselineResponse(
    val entries: List<BlueprintCompetencyResponse>,
)
