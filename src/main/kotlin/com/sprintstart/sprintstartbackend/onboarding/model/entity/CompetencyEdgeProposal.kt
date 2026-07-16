package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * An AI-proposed prerequisite edge awaiting PM review.
 *
 * Approving a proposal ([com.sprintstart.sprintstartbackend.onboarding.service.CompetencyProposalService.approveEdge])
 * requires both [fromKey] and [toKey] to already exist as live [Competency] rows -- an edge can
 * never be approved ahead of a still-pending endpoint, since that would leave a dangling
 * reference if the endpoint's own proposal is later rejected. On approval a real [CompetencyEdge]
 * is created and the change is recorded via
 * [com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphVersionService].
 */
@Entity
@Table(name = "competency_edge_proposals")
class CompetencyEdgeProposal(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "from_key", nullable = false)
    val fromKey: String,
    @Column(name = "to_key", nullable = false)
    val toKey: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: EdgeKind = EdgeKind.PREREQUISITE,
    @Column(nullable = true, columnDefinition = "TEXT")
    val rationale: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ProposalStatus = ProposalStatus.PROPOSED,
    @Column(name = "corpus_fingerprint", nullable = true)
    val corpusFingerprint: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "decided_at", nullable = true)
    var decidedAt: Instant? = null,
    @Column(name = "rejection_reason", nullable = true, columnDefinition = "TEXT")
    var rejectionReason: String? = null,
)
