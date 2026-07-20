package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepType
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "onboarding_steps")
class OnboardingStep(
    @Id
    val id: UUID = UUID.randomUUID(),
    // This is a foreign key into onboarding_paths
    @ManyToOne
    @JoinColumn(name = "phase_id", nullable = false)
    val phase: OnboardingPhase,
    @Column(nullable = false)
    var position: Int,
    @Column(nullable = false)
    var title: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    var description: String,
    @Column(nullable = true)
    var type: StepType,
    @Column(nullable = true)
    var estimatedMinutes: Int,
    @OneToMany(
        mappedBy = "step",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    @OrderBy("position")
    val tasks: MutableList<OnboardingTask> = mutableListOf(),
    @OneToMany(
        mappedBy = "step",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    val resources: MutableList<OnboardingResource> = mutableListOf(),
    @Column(nullable = false, columnDefinition = "TEXT")
    var expectedOutcome: String,
    /**
     * The competency this step teaches, carried over from the blueprint step it came from.
     *
     * This is what turns a step into a *module*: on generation a default [Verification] is created
     * for it, and the competency path uses that verification to point the matching graph node at
     * this step. A step with no key is still a step -- it just has no node to open from.
     */
    @Column(name = "competency_key", nullable = true)
    var competencyKey: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: StepStatus,
    @Column(nullable = true)
    var startedAt: Instant? = null,
    @Column(nullable = true)
    var completedAt: Instant? = null,
    @OneToMany(
        mappedBy = "step",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    @OrderBy("createdAt ASC")
    val skips: MutableList<OnboardingSkip> = mutableListOf(),
    @OneToMany(
        mappedBy = "step",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    @OrderBy("createdAt ASC")
    val feedback: MutableList<OnboardingFeedback> = mutableListOf(),
    // Grounded lesson body, synthesized by the AI service (Phase 3, #8). Null until synthesized.
    @Column(nullable = true, columnDefinition = "TEXT")
    var content: String? = null,
    // The AI service's corpus fingerprint for the current `content`, round-tripped on the next
    // synthesis call so an unchanged corpus doesn't regenerate it.
    @Column(nullable = true)
    var lessonFingerprint: String? = null,
)
