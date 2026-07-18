package com.sprintstart.sprintstartbackend.onboarding.model.response.path

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import java.util.UUID

data class PathNode(
    val key: String,
    val label: String,
    val kind: CompetencyKind,
    val state: NodeState,
    val level: Int? = null,
    // The onboarding step whose Verification.competencyKey matches this node, if one is
    // configured -- lets a client open this node as a learn-verify module (#8/#5). Null when no
    // step has been wired up to teach/verify this competency yet.
    val stepId: UUID? = null,
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
)
