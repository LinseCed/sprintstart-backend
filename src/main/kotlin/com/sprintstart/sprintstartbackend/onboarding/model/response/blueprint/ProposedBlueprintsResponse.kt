package com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint

/**
 * List of blueprints currently awaiting PM review (status PROPOSED).
 *
 * @property blueprints The proposed blueprints, each carried as a [BlueprintResponse].
 */
data class ProposedBlueprintsResponse(
    val blueprints: List<BlueprintResponse>,
)
