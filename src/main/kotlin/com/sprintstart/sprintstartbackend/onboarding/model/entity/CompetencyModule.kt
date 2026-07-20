package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContentProvenance
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModuleStatus
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * What a competency teaches: one shared, versioned, PM-authorable module per
 * `(competencyKey, projectId)`.
 *
 * **This is the artifact that did not exist before.** Content used to live on `OnboardingStep`,
 * which hangs off a per-user `OnboardingPath` — so there was no "the module for competency X",
 * only N independently generated private copies, one per hire. A PM editing a node's content was
 * editing one person's copy; two hires learning the same thing got different material; the
 * content-quality loop improved a single hire's lesson. Making the module the shared artifact is
 * what makes authoring, review, and quality work mean anything.
 *
 * Scoped per project because content is grounded in *that project's* corpus, while the
 * [Competency] itself stays global — so "earn once, transfers across projects" is preserved: the
 * ledger records the competency, not the module a hire happened to learn it from.
 *
 * Proposal-only, like blueprints and graph proposals: at most one [ModuleStatus.ACTIVE] version
 * per `(competencyKey, projectId)`, and only an explicit PM approval puts one there.
 */
@Entity
@Table(
    name = "competency_modules",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_competency_modules_key_project_version",
            columnNames = ["competency_key", "project_id", "version"],
        ),
    ],
)
class CompetencyModule(
    @Id
    val id: UUID = UUID.randomUUID(),
    /**
     * The competency this module teaches, by its stable key — never by id, matching the ledger and
     * the graph edges, so a module survives a competency being renamed or re-seeded.
     */
    @Column(name = "competency_key", nullable = false)
    val competencyKey: String,
    /**
     * The project whose corpus this module is grounded in. Not nullable: content that claims no
     * project is content grounded in nothing.
     */
    @Column(name = "project_id", nullable = false)
    val projectId: UUID,
    @Column(nullable = false)
    val version: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ModuleStatus,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val origin: ContentProvenance = ContentProvenance.PM,
    @Column(nullable = false)
    var title: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    var summary: String? = null,
    /**
     * Corpus fingerprint this module's AI-authored content was synthesized from. Lets a
     * re-synthesis pass skip an unchanged corpus, the same idempotency mechanism blueprint
     * generation and lesson synthesis already use.
     */
    @Column(name = "corpus_fingerprint", nullable = true)
    var corpusFingerprint: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @OneToMany(mappedBy = "module", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("position ASC")
    val pages: MutableList<ModulePage> = mutableListOf(),
)
