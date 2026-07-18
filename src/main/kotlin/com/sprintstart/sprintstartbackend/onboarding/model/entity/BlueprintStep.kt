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

@Entity
@Table(name = "blueprint_steps")
class BlueprintStep(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne
    @JoinColumn(name = "blueprint_id", nullable = false)
    val blueprint: Blueprint,
    @Column(nullable = false)
    val stepId: String,
    @Column(nullable = false)
    val title: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    val description: String? = null,
    @Column(nullable = true)
    val minExperience: String? = null,
    @Column(nullable = false, columnDefinition = "TEXT")
    val audience: String = "",
    @Column(nullable = false)
    val position: Int,
    @Column(nullable = false)
    val requirement: String = "recommended",
    @Column(nullable = false)
    val invariant: Boolean = false,
    // Per-step PM review, independent of the whole Blueprint's own DRAFT/PROPOSED/ACTIVE
    // lifecycle -- a PM can curate individual steps within a proposal before (or regardless of)
    // the whole-blueprint decision. REJECTED steps are excluded from Blueprint.toSchema() (what
    // actually reaches personalization/regeneration) but kept here as an audit trail, same
    // convention as CompetencyEdgeProposal.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ProposalStatus = ProposalStatus.PROPOSED,
    @Column(name = "decided_at", nullable = true)
    var decidedAt: Instant? = null,
    @Column(name = "rejection_reason", nullable = true, columnDefinition = "TEXT")
    var rejectionReason: String? = null,
)
