package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContentProvenance
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModulePageKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One ordered page of a [CompetencyModule] — a real row, which is the point.
 *
 * Pages used to be *derived on read* from a step's shape (`OnboardingStepMapper.derivePages`): at
 * most one lesson from a single content blob, one task marker, one verify marker. Nothing an
 * author could add, reorder, or edit, because pages were not stored. They are now.
 */
@Entity
@Table(name = "module_pages")
class ModulePage(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne
    @JoinColumn(name = "module_id", nullable = false)
    val module: CompetencyModule,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: ModulePageKind,
    @Column(nullable = false)
    var title: String,
    /**
     * The page body. Null for pages that carry no prose of their own — a
     * [ModulePageKind.VERIFY] page renders the module's verification, which the client fetches
     * through the attempt endpoints rather than reading from here.
     */
    @Column(nullable = true, columnDefinition = "TEXT")
    var body: String? = null,
    @Column(nullable = false)
    var position: Int,
    /**
     * Who wrote this page. Re-synthesis may replace [ContentProvenance.AI] pages and must leave
     * [ContentProvenance.PM] ones alone — otherwise regenerating a module silently discards a
     * human's edits.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var provenance: ContentProvenance = ContentProvenance.PM,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
