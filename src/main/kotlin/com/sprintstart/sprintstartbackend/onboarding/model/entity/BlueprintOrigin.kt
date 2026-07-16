package com.sprintstart.sprintstartbackend.onboarding.model.entity

/**
 * Records who authored a [Blueprint].
 *
 * [PM] blueprints are created manually by a project manager; [AI_PROPOSED] blueprints come from
 * the AI generation flow. Both must still be approved before going ACTIVE — origin captures
 * provenance, not trust level.
 */
enum class BlueprintOrigin { PM, AI_PROPOSED }
