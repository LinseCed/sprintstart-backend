package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One recorded bump of the competency graph, append-only -- like [Blueprint], each version is its
 * own row rather than a mutated counter.
 *
 * [classification] is computed by
 * [GraphChangeClassifier][com.sprintstart.sprintstartbackend.onboarding.service.GraphChangeClassifier]
 * from the [CompetencyGraphChange] rows recorded for this version, never declared by the caller
 * that triggered the bump. [GraphReconciliationService]
 * [com.sprintstart.sprintstartbackend.onboarding.service.GraphReconciliationService] uses it to
 * decide whether a version's content is safe to show a hire immediately or must wait for their
 * next session start.
 */
@Entity
@Table(name = "competency_graph_version")
class CompetencyGraphVersion(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true)
    val version: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val classification: ChangeClassification,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
