package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A conversation happened.
 *
 * The first onboarding fact that cannot be derived from anything else. Assignments, claimed tasks
 * and pull requests all leave durable traces the metrics read after the fact; two people talking
 * leaves none, so it is recorded here or it is lost.
 *
 * Deliberately thin. [note] is optional and free text, and nothing reads it — it exists so the
 * pair can leave themselves a reminder, not so the system can analyse what was said. What is
 * actually measured is that contact happened and when, because frequency is the variable that
 * tracked with onboarding outcomes.
 *
 * [recordedBy] keeps the record honest about its own provenance: either side may log a contact,
 * and "my buddy says we spoke" and "I say we spoke" are not quite the same claim.
 */
@Entity
@Table(name = "buddy_contacts")
class BuddyContact(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "hire_id", nullable = false)
    val hireId: UUID,
    @Column(name = "project_id", nullable = false)
    val projectId: UUID,
    @Column(name = "recorded_by", nullable = false)
    val recordedBy: UUID,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant = Instant.now(),
    @Column(name = "note", columnDefinition = "TEXT")
    val note: String? = null,
)
