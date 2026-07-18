package com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint

/**
 * Request body for rejecting one blueprint step within a proposal.
 *
 * @property reason Optional human-readable reason for the rejection.
 */
data class RejectBlueprintStepRequest(
    val reason: String? = null,
)
