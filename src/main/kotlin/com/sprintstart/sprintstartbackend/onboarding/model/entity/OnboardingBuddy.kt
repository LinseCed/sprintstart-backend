package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The person a new hire can ask, for one project.
 *
 * A peer rather than their manager, deliberately: the programme this is modelled on paired new
 * hires with well-regarded peers holding no supervisory relationship, because what a hire is
 * willing to admit not knowing depends on who is listening.
 *
 * Optional by design. A project can run without buddies — some teams have nobody to spare — and
 * everything else in the human loop still works: the wait for a response is still measured, stalls
 * are still raised. An unassigned hire simply becomes an attention item of its own rather than a
 * silent gap.
 *
 * [cadenceTargetDays] exists because contact *frequency*, not the mere existence of a buddy, is
 * what tracked with outcomes in the research this is built on — one meeting and eight meetings are
 * different interventions.
 */
@Entity
@Table(name = "onboarding_buddies")
class OnboardingBuddy(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "hire_id", nullable = false)
    val hireId: UUID,
    @Column(name = "project_id", nullable = false)
    val projectId: UUID,
    @Column(name = "buddy_id", nullable = false)
    var buddyId: UUID,
    @Column(name = "cadence_target_days", nullable = false)
    var cadenceTargetDays: Int = DEFAULT_CADENCE_TARGET_DAYS,
    @Column(name = "assigned_at", nullable = false)
    val assignedAt: Instant = Instant.now(),
) {
    companion object {
        /**
         * A week between contacts, as a starting point.
         *
         * Chosen to be frequent enough to matter and loose enough to survive a busy week; the
         * evidence says more contact is better, not that a specific number is right, so this is a
         * default a team can move rather than a rule.
         */
        const val DEFAULT_CADENCE_TARGET_DAYS = 7
    }
}
