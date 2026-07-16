package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A node in the onboarding competency graph — one durable thing a hire can be proficient in.
 *
 * Competencies are referenced everywhere by their stable [key], never by [id]: progress in the
 * ledger and edges in the graph point at the key so they survive renames and re-seeding. The
 * [id] exists only as the JPA primary key. Traversal and gap logic live in Phase 2; this entity
 * is just the persisted node the placement writes to and the path projects from.
 */
@Entity
@Table(name = "competencies")
class Competency(
    @Id
    val id: UUID = UUID.randomUUID(),
    // `key` is a reserved word in several dialects (e.g. H2); backticks tell Hibernate to
    // emit a dialect-appropriate quoted identifier.
    @Column(name = "`key`", nullable = false, unique = true)
    val key: String,
    @Column(nullable = false)
    var label: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    var description: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: CompetencyKind,
    // Optional pointer to the repository artifact this competency is grounded in (e.g. a path or module).
    @Column(nullable = true)
    var repoRef: String? = null,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
)
