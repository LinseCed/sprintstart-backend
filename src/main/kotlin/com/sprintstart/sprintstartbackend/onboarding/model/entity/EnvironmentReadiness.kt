package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * That a new hire's local environment came up, for one project — settled by evidence, not a checkbox.
 *
 * "I set up my environment" is exactly the claim a stuck person ticks before asking for help, so a
 * self-declared boolean is worthless here. This row exists only once something happened that could
 * not have happened without a working environment: a build-and-test run the hire reported from
 * their machine, or a green CI run they pushed.
 *
 * The row records *what* settled readiness and *when*, because "ready" with no evidence is the same
 * unfalsifiable claim in a different place.
 *
 * Readiness can also be **derived** rather than stored — a hire who has already opened a pull
 * request plainly got their environment working, whether or not they ran the documented command.
 * That derivation is computed on read (see `EnvironmentReadinessService`) and never written here;
 * this table holds only evidence the hire actively reported.
 */
@Entity
@Table(name = "environment_readiness")
class EnvironmentReadiness(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "hire_id", nullable = false)
    val hireId: UUID,
    @Column(name = "project_id", nullable = false)
    val projectId: UUID,
    /** When readiness was achieved — the moment the evidence describes, not when the row was written. */
    @Column(name = "ready_at", nullable = false)
    val readyAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(name = "evidence", nullable = false, length = 32)
    val evidence: EnvironmentEvidence,
    /** Free-text pointer to the evidence — a CI run URL, a one-line build summary. Never required. */
    @Column(name = "evidence_detail")
    val evidenceDetail: String? = null,
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant = Instant.now(),
)

/**
 * What settled a hire's environment readiness.
 *
 * [BUILD_TEST_RUN] and [GREEN_CI] are reported by the hire (the documented command posts one,
 * authenticated as them). [PULL_REQUEST] is **derived only** — never reported, never stored — and
 * stands in for "a commit authored by them" because in this system a commit carries a git author
 * *name*, not a GitHub account, so it cannot be attributed to a hire; a pull request they authored
 * can be, and is strictly stronger evidence that the environment works.
 */
enum class EnvironmentEvidence {
    BUILD_TEST_RUN,
    GREEN_CI,
    PULL_REQUEST,
}
