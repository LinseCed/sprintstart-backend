package com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint

/**
 * Request body for approving a proposed blueprint version for a scope.
 *
 * @property version The proposed version to approve and activate.
 */
data class ApproveBlueprintRequest(
    val version: String,
)
