package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * An AI-mined starter-work task (a GitHub issue) awaiting PM review.
 *
 * Mirrors [CompetencyProposal]'s proposal-only relationship with the live graph, adapted to a
 * single AI-mined artifact per row instead of a node/edge pair. Approving a proposal
 * ([com.sprintstart.sprintstartbackend.onboarding.service.StarterWorkTaskProposalService.approve])
 * creates a real [Competency] of kind `CONTRIBUTION` -- the graph's terminal/goal-node kind --
 * plus `PREREQUISITE` edges from each of [competencyKeys], so the task becomes a reachable goal
 * node once a hire has built the skills it requires. The proposal row itself is never promoted in
 * place, and this table is never read by graph traversal/projection.
 */
@Entity
@Table(name = "starter_work_task_proposals")
class StarterWorkTaskProposal(
    @Id
    val id: UUID = UUID.randomUUID(),
    // The backend's stable GitHub issue identifier (e.g. "github:org/repo:ISSUE:123"), unique
    // per proposal so the same issue is never mined into two rows.
    @Column(name = "source_id", nullable = false, unique = true)
    val sourceId: String,
    @Column(nullable = false)
    val title: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    val summary: String? = null,
    @Column(nullable = true, columnDefinition = "TEXT")
    val rationale: String? = null,
    @Column(name = "source_url", nullable = true)
    val sourceUrl: String? = null,
    // The competency keys the AI judged this task exercises; approval wires each as a
    // PREREQUISITE edge into the resulting CONTRIBUTION node.
    @ElementCollection
    @CollectionTable(
        name = "starter_work_task_proposal_competency_keys",
        joinColumns = [JoinColumn(name = "starter_work_task_proposal_id")],
    )
    @Column(name = "competency_key", nullable = false)
    val competencyKeys: MutableList<String> = mutableListOf(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ProposalStatus = ProposalStatus.PROPOSED,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "decided_at", nullable = true)
    var decidedAt: Instant? = null,
    @Column(name = "rejection_reason", nullable = true, columnDefinition = "TEXT")
    var rejectionReason: String? = null,
)
