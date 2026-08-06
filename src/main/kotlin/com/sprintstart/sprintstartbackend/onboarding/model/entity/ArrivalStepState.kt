package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.Rigor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * That one hire has settled one [ArrivalStep], and how it was established.
 *
 * ### The row's existence is the state
 *
 * There is no status column. A row means settled; no row means not settled yet. ⚠️ The only value
 * such a column could hold is `SETTLED`, and a single-valued enum is dead wiring. A future
 * "not applicable to me" is a real state worth adding *when something reads it* — adding it now
 * would be a column nobody queries.
 *
 * Absence therefore means "not settled yet", never an error, and a hire with no rows at all is a
 * hire who has just arrived — which is the normal case, not a missing-data case.
 *
 * ### Keyed by the step's key, not by its id
 *
 * [stepKey] is a string, not a foreign key to [ArrivalStep.id]. That is the ledger pattern
 * `UserCompetencyState` and `VerificationAttempt` already follow, and it is the reason five
 * separate deletions in this codebase left a hire's history intact: a definition can be deleted,
 * renamed in its wording, or re-added, and what somebody already did survives it. A cascade would
 * make deleting a step from an authoring screen quietly destroy other people's records.
 *
 * ### Monotonic
 *
 * Settled does not become unsettled. A derivation that later cannot see its evidence — GitHub
 * unreachable, a crawl that has not run — is not evidence of anything, and the standing rule across
 * this codebase is that an outage is never a statement about the world. Writes are therefore
 * idempotent: re-settling an already-settled step leaves the original [settledAt] alone, because
 * the day something happened does not move.
 *
 * ### Rigor lives here, not on the definition
 *
 * [ArrivalStep.settledBy] says how a step is *meant* to be settled; [rigor] records how it actually
 * was for this hire. They can differ, and the distinction has to survive to the readout: a step
 * the system observed and a step somebody ticked are different facts. ⚠️ Nothing may render them
 * as one figure.
 */
@Entity
@Table(name = "arrival_step_states")
class ArrivalStepState(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    /** The [ArrivalStep.key] this settles. Deliberately not a foreign key — see the class KDoc. */
    @Column(name = "step_key", nullable = false)
    val stepKey: String,
    /**
     * The scope of the step this settles: null for a company-wide step.
     *
     * Carried so that a project-scoped step and a company-wide step sharing a key remain separate
     * facts. As on [ArrivalStep], null-versus-null means the uniqueness of
     * `(user_id, step_key, project_id)` needs two partial indexes rather than one, and matching
     * enforcement in the service for the schema-from-entities test suite.
     */
    @Column(name = "project_id", nullable = true)
    val projectId: UUID? = null,
    /** How this hire's step was established: observed by the system, or declared by them. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val rigor: Rigor,
    @Column(name = "settled_at", nullable = false)
    val settledAt: Instant = Instant.now(),
)
