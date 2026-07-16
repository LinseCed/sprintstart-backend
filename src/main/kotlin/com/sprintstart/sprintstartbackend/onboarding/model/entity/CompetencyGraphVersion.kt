package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The competency graph's current version, as a single row.
 *
 * Bumped only when the graph actually changes (see
 * [com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphVersionService]) so a
 * computed path can pin the version it was projected against. Intentionally minimal: no history
 * table or per-change audit trail yet -- there's no real way to edit the graph or classify a
 * change today, so that's left for a later slice.
 */
@Entity
@Table(name = "competency_graph_version")
class CompetencyGraphVersion(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    var version: Int = 1,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
