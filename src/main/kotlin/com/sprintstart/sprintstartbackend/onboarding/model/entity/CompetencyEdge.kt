package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

/**
 * A directed relationship between two competencies in the graph.
 *
 * Endpoints are referenced by stable competency key ([fromKey], [toKey]), not by FK UUID, so
 * edges survive node re-seeding and renames. The pair is unique per [kind] — the same two
 * competencies may have at most one edge of each kind. [weight] lets Phase 2 traversal rank
 * prerequisites; it defaults to a neutral 1.0 and carries no meaning yet.
 */
@Entity
@Table(
    name = "competency_edges",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_competency_edges_from_to_kind",
            columnNames = ["from_key", "to_key", "kind"],
        ),
    ],
)
class CompetencyEdge(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "from_key", nullable = false)
    val fromKey: String,
    @Column(name = "to_key", nullable = false)
    val toKey: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: EdgeKind,
    @Column(nullable = false)
    val weight: Double = 1.0,
)
