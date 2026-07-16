package com.sprintstart.sprintstartbackend.onboarding.model.entity

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
    @OneToMany(mappedBy = "blueprint", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("position ASC")
    val steps: MutableList<BlueprintStep> = mutableListOf(),
)
