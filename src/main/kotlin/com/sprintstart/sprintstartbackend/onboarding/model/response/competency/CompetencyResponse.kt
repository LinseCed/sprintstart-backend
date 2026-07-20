package com.sprintstart.sprintstartbackend.onboarding.model.response.competency

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind

/**
 * A live competency node as a PM authoring it sees it.
 *
 * [key] is returned but never accepted as input -- a PM needs to see the identity the ledger is
 * keyed by, and needs to be told it is not what they are renaming.
 */
data class CompetencyResponse(
    val key: String,
    val label: String,
    val description: String?,
    val kind: CompetencyKind,
    val targetLevel: Int,
    val invariant: Boolean,
    val repoRef: String?,
)

/** A live competency edge as a PM authoring it sees it. */
data class CompetencyEdgeResponse(
    val fromKey: String,
    val toKey: String,
    val kind: EdgeKind,
)

/**
 * The outcome of removing a competency node from the graph.
 *
 * [edgesRemoved] is surfaced because deleting one node silently detaches every edge touching it,
 * and a PM should see how much of the graph's structure that took with it.
 */
data class DeleteCompetencyResponse(
    val key: String,
    val edgesRemoved: Int,
    val graphVersion: Int,
)

/** The outcome of approving a set of proposals as one graph version. */
data class ApproveGraphBatchResponse(
    val competenciesApproved: Int,
    val edgesApproved: Int,
    val graphVersion: Int,
)
