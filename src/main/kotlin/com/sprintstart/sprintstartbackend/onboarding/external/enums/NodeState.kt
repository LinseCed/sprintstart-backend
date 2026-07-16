package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * A competency node's state in a hire's projected path.
 *
 * [MASTERED] comes straight from the ledger
 * ([com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState]) — any
 * non-zero level counts, since there's no separate "target level per node" concept yet.
 * [AVAILABLE] means every direct [EdgeKind.PREREQUISITE] predecessor is mastered (or there are
 * none); otherwise the node is [LOCKED]. [EdgeKind.RELATED] edges never affect state.
 */
enum class NodeState {
    MASTERED,
    AVAILABLE,
    LOCKED,
}
