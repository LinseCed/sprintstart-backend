package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A competency somebody deliberately removed, remembered so the generator cannot bring it back.
 *
 * ### Why this exists
 *
 * Dedup matches on the exact key **and** on embedding similarity. Without a memory of the deletion,
 * a competency a PM removes returns on the next crawl — under a rephrasing, so even the key check
 * misses it — and they delete it again, and again. Deletion leaks. This is the board's rule applied
 * to the generator: **a dismissal is sticky and binds the mentor too.**
 *
 * ### Why a table rather than a flag on `Competency`
 *
 * The design of record says a deleted competency "keeps its row, marked deleted". That needs **every
 * existing reader** to remember to filter the flag out — there are eight, across the studio, the
 * dashboard, module authoring, verification, the ramp and starter-work matching — and any reader
 * added later that forgets creates a competency that is deleted but still visible. That is exactly
 * the ghost-row failure the graph-visibility replay already has, and it is not worth re-creating for
 * a property that a separate table gives for free: **no reader can forget a table it does not
 * query.** Removal stays the real delete S0 made it; this remembers that it happened.
 *
 * ### What it carries, and why that much
 *
 * The **label**, not just the key, because the point is to block a *rephrasing*. The generator is
 * given these the way it is given live competencies — as `key: label` — and the label is what the
 * similarity check embeds. A tombstone the generator never sees is not a tombstone.
 *
 * ### What overrides it
 *
 * A person. Hand-authoring the same key again clears the tombstone: the rule binds the generator,
 * not the PM who changed their mind. Their earned levels and any authored module were never touched
 * — both are keyed by the competency key — so re-adding it restores the module with it.
 */
@Entity
@Table(name = "competency_tombstones")
class CompetencyTombstone(
    @Id
    val id: UUID = UUID.randomUUID(),
    // `key` is a reserved word in several dialects (e.g. H2); backticks tell Hibernate to
    // emit a dialect-appropriate quoted identifier.
    @Column(name = "`key`", nullable = false, unique = true)
    val key: String,
    /** What it was called, so a re-proposal can be recognised by meaning and not only by key. */
    @Column(nullable = false)
    var label: String,
    @Column(name = "deleted_at", nullable = false)
    var deletedAt: Instant = Instant.now(),
)
