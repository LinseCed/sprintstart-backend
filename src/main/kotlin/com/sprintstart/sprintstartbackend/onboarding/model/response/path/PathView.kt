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
)
