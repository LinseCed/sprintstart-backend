package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * The starter-work task a hire has claimed as their goal, per project.
 *
 * The north star is time-to-first-contribution, so a hire aims at a piece of real work rather than
 * at a position in a curriculum. This row is what makes that concrete.
 *
 * ### Why this points at the proposal
 *
 * It used to name a `CompetencyKind.CONTRIBUTION` competency minted when a PM approved the task, so
 * that a path could add the node's transitive prerequisites to its target set. With the graph's
 * structure retired there are no prerequisites to add, and a contribution node was a competency
 * nobody could ever be assessed on -- so the indirection bought nothing and could break: a goal
 * stopped resolving whenever the node and the proposal table disagreed.
 *
 * ### Why this is stored rather than derived
 *
 * Hire→task matching is an AI call. Running it per read would put a model round trip on a hot path
 * and would let a hire's destination change under them between two page loads because the ranking
 * moved. The hire claims one from their ranked matches and it stays claimed until they change it.
 */
@Entity
@Table(
    name = "user_goals",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_user_goals_user_project", columnNames = ["user_id", "project_id"]),
    ],
)
class UserGoal(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "project_id", nullable = false)
    val projectId: UUID,
    /** The starter-work task being worked toward. */
    @Column(name = "source_proposal_id", nullable = false)
    var sourceProposalId: UUID,
    @Column(name = "claimed_at", nullable = false)
    var claimedAt: Instant = Instant.now(),
)
