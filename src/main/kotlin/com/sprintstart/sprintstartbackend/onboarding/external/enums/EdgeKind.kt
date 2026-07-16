package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * The relationship a competency edge expresses between two competencies.
 *
 * [PREREQUISITE] edges are directed dependencies: the `to` competency requires the `from`
 * competency first, and they drive traversal and gating (Phase 2). [RELATED] edges are
 * non-gating associations used for grouping and recommendation.
 */
enum class EdgeKind {
    PREREQUISITE,
    RELATED,
}
