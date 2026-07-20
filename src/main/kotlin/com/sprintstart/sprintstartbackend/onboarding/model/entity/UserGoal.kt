package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * The contribution a hire has claimed as the destination of their path, per project.
 *
 * The north star is time-to-first-contribution, so a path should aim at a shipped change rather
 * than span the graph. This row is what makes that concrete: it names the `CONTRIBUTION`
 * competency a hire is working toward, and
 * [CompetencyPathService][com.sprintstart.sprintstartbackend.onboarding.service.CompetencyPathService]
 * adds it to the path's target set so its transitive prerequisites -- and only those, beyond the
 * project's baseline -- appear on the path.
 *
 * ### Why this is stored rather than derived
 *
 * Hire→task matching is an AI call. Running it on every `GET /me/path` would put a model round
 * trip on the hottest read in the product, and would let a hire's destination change under them
 * between two page loads because the ranking moved. The hire claims one from their ranked matches
 * and it stays claimed until they change it.
 *
 * ### Identity
 *
 * [competencyKey] points at the CONTRIBUTION node, not at the proposal that created it -- the same
 * reasoning as everywhere else in the ledger: the key is the durable identity, and a path is
 * projected from the graph, not from the proposal table. [sourceProposalId] is kept so a client
 * can show the underlying task (its issue link, summary, scope rationale) without re-deriving
 * which proposal minted the node.
 *
 * Per `(userId, projectId)`: onboarding is per-project, so a hire onboarding onto two projects
 * aims at a different contribution in each.
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
    /** The CONTRIBUTION competency this path aims at. */
    @Column(name = "competency_key", nullable = false)
    var competencyKey: String,
    /** The starter-work proposal that minted that node, for showing the underlying task. */
    @Column(name = "source_proposal_id", nullable = true)
    var sourceProposalId: UUID? = null,
    @Column(name = "claimed_at", nullable = false)
    var claimedAt: Instant = Instant.now(),
)
