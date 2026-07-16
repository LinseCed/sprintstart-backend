package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
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
 * An AI-proposed competency node awaiting PM review.
 *
 * Approving a proposal
 * ([com.sprintstart.sprintstartbackend.onboarding.service.CompetencyProposalService.approveCompetency])
 * creates a real [Competency] with this proposal's [key]/[label]/[description]/[kind]/[repoRef]
 * and records the change via
 * [com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphVersionService]
 * -- the proposal row itself is never promoted in place, and this table is never read by
 * graph traversal/projection.
 */
@Entity
@Table(name = "competency_proposals")
class CompetencyProposal(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "`key`", nullable = false)
    val key: String,
    @Column(nullable = false)
    val label: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    val description: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: CompetencyKind,
    @Column(name = "repo_ref", nullable = true)
    val repoRef: String? = null,
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
