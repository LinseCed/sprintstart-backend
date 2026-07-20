package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A hire's reaction to a piece of onboarding content.
 *
 * Attached to a [ModulePage], which is what makes the content-quality loop mean anything: the page
 * is shared, so "this didn't help" is a signal about the material everybody reads, not about one
 * person's private copy of it. Feedback used to hang off a per-user `OnboardingStep`, where three
 * hires disliking the same lesson produced three unrelated counts of one.
 */
@Entity
@Table(name = "onboarding_feedback")
class OnboardingFeedback(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val userId: UUID,
    // Nullable: feedback can be about onboarding in general, not a specific page.
    @ManyToOne
    @JoinColumn(name = "page_id", nullable = true)
    var page: ModulePage? = null,
    @Column(nullable = true)
    var helpful: Boolean? = null,
    @Column(nullable = false, columnDefinition = "TEXT")
    var message: String,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(nullable = false)
    var read: Boolean = false,
)
