package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One atomic node or edge mutation recorded against a pending competency graph version, before
 * that version is bumped.
 *
 * [GraphChangeClassifier][com.sprintstart.sprintstartbackend.onboarding.service.GraphChangeClassifier]
 * reads the rows for a version to compute its [ChangeClassification]
 * [com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification], and
 * [EffectiveGraphResolver][com.sprintstart.sprintstartbackend.onboarding.service.EffectiveGraphResolver]
 * replays rows across versions to determine which nodes/edges are visible to a given hire. Node
 * changes populate [competencyKey]; edge changes populate [fromKey]/[toKey]/[edgeKind]. This
 * table only ever gains rows -- it is never mutated once written.
 */
@Entity
@Table(name = "competency_graph_changes")
class CompetencyGraphChange(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val version: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    val changeType: ChangeType,
    @Column(name = "competency_key", nullable = true)
    val competencyKey: String? = null,
    @Column(name = "from_key", nullable = true)
    val fromKey: String? = null,
    @Column(name = "to_key", nullable = true)
    val toKey: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "edge_kind", nullable = true)
    val edgeKind: EdgeKind? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
