package com.sprintstart.sprintstartbackend.onboarding.model.request.competency

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import java.util.UUID

/**
 * A PM's edit to one competency node.
 *
 * Every field is optional: an omitted field is left alone, so a client can change a label without
 * restating the rest.
 *
 * [key] is deliberately absent. It is the ledger's identity for this competency -- every
 * `UserCompetencyState` row, every graph edge and every module points at it -- so renaming it
 * would orphan everything somebody has earned. The **label** is what a PM renames.
 */
data class UpdateCompetencyRequest(
    val label: String? = null,
    val description: String? = null,
    val kind: CompetencyKind? = null,
    /** The proficiency rank (1..4) a hire must reach for this node to count as met. */
    val targetLevel: Int? = null,
    /** Compliance-flagged: any change touching this node pushes to hires immediately. */
    val invariant: Boolean? = null,
)

/**
 * A PM's hand-authored competency node, created without any AI proposal.
 *
 * This is the piece that makes AI genuinely optional for the graph: until it existed, a node's
 * only way in was to approve an AI-generated proposal, so a PM could edit or delete a node by hand
 * but never *originate* one. AI mining stays available as the other way to seed the graph.
 *
 * [key] is the ledger's permanent identity for this competency and is slugified on the way in
 * (lower-cased, non-alphanumerics collapsed to `-`) so hand-typed keys share the house style of
 * generated ones and are safe to carry in a URL. It is the one field creation gets to set and
 * editing never can -- see [UpdateCompetencyRequest].
 */
data class CreateCompetencyRequest(
    val key: String,
    val label: String,
    val description: String? = null,
    val kind: CompetencyKind,
    /**
     * The proficiency rank (1..4) a hire must reach for this node to count as met. Omitted, it
     * takes the same intermediate default a proposed node gets (`Competency.DEFAULT_TARGET_LEVEL`).
     */
    val targetLevel: Int? = null,
    /** Compliance-flagged: any change touching this node pushes to hires immediately. */
    val invariant: Boolean = false,
)

/**
 * A PM's hand-authored edge between two existing competencies.
 *
 * Indistinguishable from an approved AI-proposed edge once created -- the edge table carries no
 * provenance, and the fact that a human added it lives in the change-row history, not on the edge.
 */
data class CreateCompetencyEdgeRequest(
    val fromKey: String,
    val toKey: String,
    val kind: EdgeKind = EdgeKind.PREREQUISITE,
)

/**
 * A set of proposals a PM approves as one unit.
 *
 * Exists so a node and the edges into it land in the *same* graph version. Approved one at a
 * time, an edge into an already-live node classifies STRUCTURAL and is held back, so the node
 * shows up as an orphan `AVAILABLE` node and can re-lock at the hire's next session start once
 * its prerequisites finally appear. Batched,
 * [GraphChangeClassifier][com.sprintstart.sprintstartbackend.onboarding.service.GraphChangeClassifier]
 * sees the node as newly introduced in the same batch and classifies the whole subgraph ADDITIVE.
 */
data class ApproveGraphBatchRequest(
    val competencyProposalIds: List<UUID> = emptyList(),
    val edgeProposalIds: List<UUID> = emptyList(),
)
