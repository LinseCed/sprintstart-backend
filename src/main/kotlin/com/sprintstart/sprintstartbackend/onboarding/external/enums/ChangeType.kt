package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * The kind of atomic mutation a [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange]
 * record describes.
 *
 * All variants except [EDGE_MODIFIED] are now written by real code paths:
 * [CompetencyGraphSeeder][com.sprintstart.sprintstartbackend.onboarding.seeding.CompetencyGraphSeeder]
 * and proposal approval insert, and
 * [CompetencyGraphAuthoringService][com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphAuthoringService]
 * records a PM's edits and removals. [EDGE_MODIFIED] has no writer: an edge is identified by
 * `(fromKey, toKey, kind)` and carries nothing else a PM can change, so editing one is
 * expressed as a removal plus an addition.
 *
 * [NODE_REMOVED] and [EDGE_REMOVED] mean *removed from the visible graph*, not deleted -- the
 * underlying rows stay and simply stop being replayed into the visible set, so a removal is
 * reversible and takes nothing off anybody's ledger.
 */
enum class ChangeType {
    NODE_ADDED,
    NODE_REMOVED,
    NODE_MODIFIED,
    EDGE_ADDED,
    EDGE_REMOVED,
    EDGE_MODIFIED,
}
