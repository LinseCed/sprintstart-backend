package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One entry of a [Blueprint]: a competency everybody in the blueprint's scope must reach, and
 * the level that counts as reaching it.
 *
 * This is the whole content of a baseline: *which competencies must everyone in this scope reach,
 * and to what level*. That is a selection over the competency graph, so the selection is what is
 * stored -- no parallel content model of its own.
 *
 * Entries reference a competency by its stable [competencyKey], never by id -- same rule as the
 * ledger and the graph edges, so a selection survives renames and re-seeding.
 */
@Entity
@Table(name = "blueprint_competencies")
class BlueprintCompetency(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne
    @JoinColumn(name = "blueprint_id", nullable = false)
    val blueprint: Blueprint,
    @Column(name = "competency_key", nullable = false)
    val competencyKey: String,
    /**
     * The level a hire in this scope must reach for the entry to count as met, or null to use
     * the competency's own [Competency.targetLevel].
     *
     * A per-baseline override exists because the bar is a property of *this team's expectations*,
     * not only of the competency: the same node can be a passing acquaintance in one project and
     * a core skill in another. Null is the normal case -- the graph's bar already applies.
     */
    @Column(name = "target_level", nullable = true)
    var targetLevel: Int? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var requirement: BlueprintRequirement = BlueprintRequirement.RECOMMENDED,
    /**
     * Compliance/mandate-flagged entries the AI may not remove or downgrade on regeneration, and
     * a PM may not reject piecemeal -- the protection would be meaningless if either could.
     */
    @Column(nullable = false)
    var invariant: Boolean = false,
    /**
     * Why this competency belongs in the baseline, in the proposer's words. Review-facing only:
     * a PM deciding whether everyone must reach a node needs the argument for it, not just the
     * name. Never read by path projection.
     */
    @Column(nullable = true, columnDefinition = "TEXT")
    var rationale: String? = null,
    @Column(nullable = false)
    var position: Int,
    /**
     * Per-entry PM review, independent of the whole [Blueprint]'s DRAFT/PROPOSED/ACTIVE
     * lifecycle -- a PM can curate individual entries within a proposal before (or regardless of)
     * the whole-blueprint decision. REJECTED entries are excluded from everything downstream but
     * kept here as an audit trail, same convention as `CompetencyEdgeProposal`.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ProposalStatus = ProposalStatus.PROPOSED,
    @Column(name = "decided_at", nullable = true)
    var decidedAt: Instant? = null,
    @Column(name = "rejection_reason", nullable = true, columnDefinition = "TEXT")
    var rejectionReason: String? = null,
)
