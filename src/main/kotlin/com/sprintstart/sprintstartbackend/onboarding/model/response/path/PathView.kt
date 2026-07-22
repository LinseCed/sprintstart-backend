package com.sprintstart.sprintstartbackend.onboarding.model.response.path

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import java.util.UUID

data class PathNode(
    val key: String,
    val label: String,
    val kind: CompetencyKind,
    val state: NodeState,
    val level: Int? = null,
    /** The bar [level] is held to for this node on this path (baseline overrides applied). */
    val targetLevel: Int,
    // The live CompetencyModule that teaches this node in this project, if one has been approved
    // -- what a client opens when the hire clicks the node. Null when nobody has published a
    // module for this competency yet, which is a real and visible state: the node is on the path
    // but there is nothing to open.
    val moduleId: UUID? = null,
    // Echoed from that module's check so a client can recognize e.g. an artifact-graded node
    // without a per-node fetch. Null when the module has no check configured.
    val verificationType: VerificationType? = null,
)

data class PathEdge(
    val from: String,
    val to: String,
)

/**
 * A hire's personalized competency path: [nodes] to be reached and the [PathEdge] prerequisite
 * (and related) relationships between them, computed by
 * [com.sprintstart.sprintstartbackend.onboarding.service.PathProjectionService].
 *
 * [graphVersion] pins the competency graph version this path was projected against (see
 * [com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphVersionService]).
 */
data class PathView(
    val nodes: List<PathNode>,
    val edges: List<PathEdge>,
    val graphVersion: Int,
    /**
     * The contribution this path aims at, or `null` when the hire hasn't claimed one.
     *
     * Named in the payload rather than left for a client to infer by scanning for
     * [CompetencyKind.CONTRIBUTION]: more than one contribution node can be on a path (the
     * project's baseline may select some), and only this one is *theirs*. A client that guessed
     * from `kind` would have no way to tell which.
     *
     * `null` is a real state with a next action ("pick something to work toward"), not a failure.
     */
    val goal: GoalView? = null,
)

/**
 * The contribution a hire is working toward, and how far off it is.
 *
 * [remainingCount] counts the nodes on the path standing between the hire and this goal -- its
 * unmet transitive prerequisites -- so a client can show progress toward *the destination* rather
 * than toward the whole graph, which is what the north star actually measures.
 */
data class GoalView(
    val competencyKey: String,
    val label: String,
    val summary: String? = null,
    /** The underlying issue, so a hire can read the actual task. */
    val sourceUrl: String? = null,
    /** The starter-work proposal that minted this node, for fetching its full detail. */
    val sourceProposalId: UUID? = null,
    /** Prerequisites of this goal the hire has not yet met. */
    val remainingCount: Int = 0,
    /** Whether every prerequisite is cleared, so the contribution itself can be started. */
    val isReachable: Boolean = false,
)
