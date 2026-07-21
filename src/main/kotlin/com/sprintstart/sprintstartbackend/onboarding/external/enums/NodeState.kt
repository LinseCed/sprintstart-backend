package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * A competency node's state in a hire's projected path.
 *
 * [MASTERED] comes straight from the ledger
 * ([com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState]): the hire's
 * level meets the node's target level. Everything else is [AVAILABLE].
 *
 * There is deliberately no "locked" state. A prerequisite edge ranks the work a hire is guided
 * toward — it never bars them from attempting a node. The map shows standing, not permission: a
 * node is either something you have shown, or something you can pick up. The edges still travel on
 * the path view to explain ordering; they no longer gate.
 */
enum class NodeState {
    MASTERED,
    AVAILABLE,
}
