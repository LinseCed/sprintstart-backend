package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * How disruptive a competency graph version's changes are, and therefore how reconciliation
 * tiers them for a hire already mid-path.
 *
 * [INVARIANT] changes touch a compliance-mandated competency and push immediately regardless of
 * shape. [ADDITIVE] changes are safe to show right away -- new nodes/edges that don't narrow
 * anything a hire may already hold. [STRUCTURAL] changes could re-lock or narrow something a hire
 * already depends on, so [GraphReconciliationService]
 * [com.sprintstart.sprintstartbackend.onboarding.service.GraphReconciliationService] holds them
 * back from a hire's live path until their next session start. Computed automatically by
 * [GraphChangeClassifier][com.sprintstart.sprintstartbackend.onboarding.service.GraphChangeClassifier],
 * never declared by the caller that triggers the change.
 */
enum class ChangeClassification {
    INVARIANT,
    ADDITIVE,
    STRUCTURAL,
}
