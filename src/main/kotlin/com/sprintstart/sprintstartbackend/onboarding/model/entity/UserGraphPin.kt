package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The competency graph version a hire's live path is currently reconciled against.
 *
 * Lazily created at the current graph version on a hire's first
 * [CompetencyPathService.getPathForMe][com.sprintstart.sprintstartbackend.onboarding.service.CompetencyPathService]
 * call. [GraphReconciliationService][com.sprintstart.sprintstartbackend.onboarding.service.GraphReconciliationService]
 * advances [pinnedVersion] to the graph's current version at the hire's next session start.
 * [EffectiveGraphResolver][com.sprintstart.sprintstartbackend.onboarding.service.EffectiveGraphResolver]
 * uses the pin to hold back STRUCTURAL changes from the live projection until then, while
 * ADDITIVE/INVARIANT changes remain visible immediately regardless of the pin.
 */
@Entity
@Table(name = "user_graph_pins")
class UserGraphPin(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false, unique = true)
    val userId: UUID,
    @Column(name = "pinned_version", nullable = false)
    var pinnedVersion: Int,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
