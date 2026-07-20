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
 * A graded check — the gate on a competency.
 *
 * Owned by a [CompetencyModule] ([moduleId]): one shared check per module, so two hires proving
 * the same competency are held to the same bar. [stepId] is the **legacy** owner, a per-user
 * [OnboardingStep], and survives only until the per-user content tree is retired (backend#53).
 * Exactly one of the two is set.
 *
 * Keeping both owners on one entity is deliberate: [VerificationAttempt] points at a
 * [Verification], so a hire's attempt history needs no migration and no repointing — the rows it
 * references simply change owner over time.
 *
 * Either owner has at most one [Verification] (enforced by the unique constraints), referenced by
 * a plain FK rather than a bidirectional JPA relationship, so this entity can be created/edited
 * independently without touching the owner's cascade footprint.
 * [competencyKey] ties this step to a [Competency] by its stable key (not a FK, matching the same
 * loosely-coupled convention `CompetencyEdge`/`BlueprintCompetency` already use), and [level] is the
 * target proficiency level being verified, aligned with the AI service's `beginner..expert` ladder
 * (see `AssessmentService.LEVEL_RANKS` for the same scale used elsewhere in this module).
 *
 * [rubric] is required for [VerificationType.KNOWLEDGE] (graded by the AI service against the
 * owner's lesson content as grounded evidence) and [canonicalAnswer] for
 * [VerificationType.EXACT] (graded locally); neither is meaningful for [VerificationType.ATTEST].
 * [rubric] is also required for [VerificationType.ARTIFACT], alongside [repositoryConnectionId] --
 * the GitHub repository connection (owned by the `connectors.github` module, referenced by plain
 * id rather than a cross-module JPA relation) a hire's submitted PR number is resolved against.
 */
@Entity
@Table(
    name = "verifications",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_verifications_step", columnNames = ["step_id"]),
        UniqueConstraint(name = "uq_verifications_module", columnNames = ["module_id"]),
    ],
)
class Verification(
    @Id
    val id: UUID = UUID.randomUUID(),
    // Legacy owner: the per-user step this check hangs off. Null for module-owned checks, which
    // is every check created from here on. Removed with the per-user tree (backend#53).
    @Column(name = "step_id", nullable = true)
    val stepId: UUID? = null,
    @Column(name = "module_id", nullable = true)
    val moduleId: UUID? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: VerificationType,
    @Column(nullable = false, columnDefinition = "TEXT")
    var prompt: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    var rubric: String? = null,
    @Column(name = "canonical_answer", nullable = true, columnDefinition = "TEXT")
    var canonicalAnswer: String? = null,
    @Column(name = "repository_connection_id", nullable = true)
    var repositoryConnectionId: UUID? = null,
    @Column(name = "competency_key", nullable = false)
    var competencyKey: String,
    @Column(nullable = false)
    var level: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
