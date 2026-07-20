package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "blueprints")
class Blueprint(
    @Id
    val id: UUID = UUID.randomUUID(),
    // The project this baseline belongs to. Onboarding is per-project: a blueprint is one
    // project's PM-owned baseline, keyed together with [scope] (the role/area) and [version].
    // Nullable for legacy/global blueprints created before per-project scoping; project-scoped
    // queries fall back to the unscoped (null-project) blueprint when a project has none of its own.
    @Column(name = "project_id", nullable = true)
    val projectId: UUID? = null,
    @Column(nullable = false)
    val scope: String,
    @Column(nullable = false)
    val version: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: BlueprintStatus,
    // Who authored this blueprint. Defaults to AI_PROPOSED since the current generation flow
    // is the only producer; PM-authored blueprints set this explicitly.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val origin: BlueprintOrigin = BlueprintOrigin.AI_PROPOSED,
    // The PM who owns/authored this blueprint, when known. Null for AI-generated proposals
    // that have not been claimed by a specific owner.
    @Column(name = "owner_id", nullable = true)
    val ownerId: UUID? = null,
    // Corpus fingerprint the AI generated this blueprint from. Round-tripped back to
    // the stateless AI service so an unchanged corpus short-circuits regeneration.
    @Column(nullable = true)
    val corpusFingerprint: String? = null,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
    // What the baseline actually is: the competencies everyone in this scope must reach. Not a
    // list of prose steps -- see BlueprintCompetency for why that model was retired.
    @OneToMany(mappedBy = "blueprint", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("position ASC")
    val competencies: MutableList<BlueprintCompetency> = mutableListOf(),
) {
    /**
     * The entries that still count: everything a PM has not rejected. `PROPOSED` (never explicitly
     * decided) and `APPROVED` both count, so a PM who never reviews individual entries sees no
     * behavior change from per-entry curation existing.
     */
    fun activeCompetencies(): List<BlueprintCompetency> =
        competencies.filter { it.status != ProposalStatus.REJECTED }
}
