package com.sprintstart.sprintstartbackend.onboarding.model.entity

/**
 * Lifecycle state of a [Blueprint].
 *
 * Blueprints are proposal-only: nothing reaches [ACTIVE] without explicit PM approval.
 * The intended flow is [DRAFT] (optional authoring state) -> [PROPOSED] (awaiting review) ->
 * [ACTIVE] (the single live baseline per scope) -> [ARCHIVED] (superseded or rejected,
 * retained for history and rollback).
 */
enum class BlueprintStatus { DRAFT, PROPOSED, ACTIVE, ARCHIVED }
