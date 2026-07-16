package com.sprintstart.sprintstartbackend.onboarding.model.response.path

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState

data class PathNode(
    val key: String,
    val label: String,
    val kind: CompetencyKind,
    val state: NodeState,
    val level: Int? = null,
)

data class PathEdge(
    val from: String,
    val to: String,
)

/**
 * A hire's personalized competency path: [nodes] to be reached and the [PathEdge] prerequisite
 * (and related) relationships between them, computed by
 * [com.sprintstart.sprintstartbackend.onboarding.service.PathProjectionService].
 */
data class PathView(
    val nodes: List<PathNode>,
    val edges: List<PathEdge>,
)
