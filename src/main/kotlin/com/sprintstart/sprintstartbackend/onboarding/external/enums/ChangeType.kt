package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * The kind of atomic mutation a [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange]
 * record describes.
 *
 * Only [NODE_ADDED] and [EDGE_ADDED] are reachable from any real production code path today --
 * [CompetencyGraphSeeder][com.sprintstart.sprintstartbackend.onboarding.seeding.CompetencyGraphSeeder]
 * only ever inserts, and no repository update/delete capability exists for [Competency]
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency] or
 * [CompetencyEdge][com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge]. The
 * remaining variants exist so [GraphChangeClassifier]
 * [com.sprintstart.sprintstartbackend.onboarding.service.GraphChangeClassifier] and
 * [EffectiveGraphResolver][com.sprintstart.sprintstartbackend.onboarding.service.EffectiveGraphResolver]
 * can be correctly exercised via fixtures ahead of Phase 5 graph-authoring.
 */
enum class ChangeType {
    NODE_ADDED,
    NODE_REMOVED,
    NODE_MODIFIED,
    EDGE_ADDED,
    EDGE_REMOVED,
    EDGE_MODIFIED,
}
