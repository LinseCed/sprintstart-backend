package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * The "Verify" zone config for one [OnboardingStep] — a graph node's check.
 *
 * A step has at most one [Verification] (enforced by the unique constraint on [stepId]),
 * referenced by a plain FK rather than a bidirectional JPA relationship, so this entity can be
 * created/edited independently without touching [OnboardingStep]'s existing cascade footprint.
 * [competencyKey] ties this step to a [Competency] by its stable key (not a FK, matching the same
 * loosely-coupled convention `CompetencyEdge`/`BlueprintStep` already use), and [level] is the
 * target proficiency level being verified, aligned with the AI service's `beginner..expert` ladder
 * (see `AssessmentService.LEVEL_RANKS` for the same scale used elsewhere in this module).
 *
 * [rubric] is required for [VerificationType.KNOWLEDGE] (graded by the AI service against the
 * step's [OnboardingStep.content] as grounded evidence) and [canonicalAnswer] for
 * [VerificationType.EXACT] (graded locally); neither is meaningful for [VerificationType.ATTEST].
 */
@Entity
@Table(
    name = "verifications",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_verifications_step", columnNames = ["step_id"]),
    ],
)
class Verification(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "step_id", nullable = false)
    val stepId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: VerificationType,
    @Column(nullable = false, columnDefinition = "TEXT")
    var prompt: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    var rubric: String? = null,
    @Column(name = "canonical_answer", nullable = true, columnDefinition = "TEXT")
    var canonicalAnswer: String? = null,
    @Column(name = "competency_key", nullable = false)
    var competencyKey: String,
    @Column(nullable = false)
    var level: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
