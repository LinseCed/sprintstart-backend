package com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint

/**
 * Request body for rejecting a proposed blueprint version for a scope.
 *
 * @property version The proposed version to reject and archive.
 * @property reason Optional human-readable reason for the rejection.
 */
data class RejectBlueprintRequest(
    val version: String,
    val reason: String? = null,
)
