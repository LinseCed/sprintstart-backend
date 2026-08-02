package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContentProvenance
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
 * One thing that has to be true before a hire can work — an account, an access grant, a machine
 * that builds.
 *
 * ### What this is not
 *
 * It is **not** a gate. Nothing in the system refuses to serve a hire because an arrival step is
 * outstanding: "not settled" is a value in a response body, never a 403. The onboarding rework
 * removed `NodeState.LOCKED` *from its enum* precisely so a gate could not be reintroduced by
 * accident, and this entity does not reintroduce one. "Mandatory" here means somebody is expected
 * to do this and it is tracked — being blocked by your employer must not also mean being blocked by
 * the tool.
 *
 * It is also **not** the per-user step tree that C4 deleted. That tree hung content off a per-user
 * `OnboardingPath`, so there was no "the step for X", only N private copies nobody could maintain.
 * This is the shape that replaced it: **one shared definition, and per-hire state beside it**
 * ([ArrivalStepState]).
 *
 * ### Scope
 *
 * [projectId] is nullable and **null means company-wide**. Account creation and HR paperwork are
 * the same on every project; making each PM re-author them is the effort this design exists to
 * avoid. This is the fourth use of a rule already load-bearing elsewhere in this codebase —
 * *absent scope is not excluded scope* — which a null track (suits any role), unscoped corpus
 * material (visible to every project) and a null `connector_id` already follow.
 *
 * A hire's list is company steps plus the steps of the projects they are on, deduplicated by
 * [key], with a project-scoped definition winning. That lets a project sharpen a company step's
 * wording without forking the key its state is stored against.
 *
 * ### Uniqueness is enforced twice, on purpose
 *
 * A [key] must be unique within its scope, but **`NULL` does not conflict with `NULL` in Postgres**
 * — so a single unique index on `(key, project_id)` would constrain project-scoped rows and
 * silently permit unlimited duplicate company-wide ones, which is every row A0 creates. The
 * migration therefore declares **two partial unique indexes**, and because Hibernate cannot express
 * a partial index at all — and the test suite builds its schema from these entities, never from the
 * migrations — [com.sprintstart.sprintstartbackend.onboarding.service.ArrivalStepService] enforces
 * the same rule in code. Neither guard is redundant: the index protects the database, the service
 * protects the tests.
 */
@Entity
@Table(name = "arrival_steps")
class ArrivalStep(
    @Id
    val id: UUID = UUID.randomUUID(),
    /**
     * The stable identifier this step is known by, immutable once created.
     *
     * [ArrivalStepState] points at this string rather than at [id], which is the ledger pattern:
     * a definition can be deleted and re-added without destroying what a hire already settled. That
     * property is what has made five deletions safe across this codebase, and it is why changing a
     * key is rejected rather than cascaded.
     *
     * The column name is backticked because `key` is a reserved word in several dialects (e.g. H2);
     * that tells Hibernate to emit a dialect-appropriate quoted identifier. Same as [Competency].
     */
    @Column(name = "`key`", nullable = false)
    val key: String,
    /** Null means company-wide; a value scopes the step to one project. */
    @Column(name = "project_id", nullable = true)
    val projectId: UUID? = null,
    @Column(nullable = false)
    var title: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    var description: String? = null,
    /** Where to go to actually do it — a request form, an internal wiki page. Optional. */
    @Column(nullable = true)
    var href: String? = null,
    /** Ordering within the step's own scope. Company steps and project steps are ordered separately. */
    @Column(nullable = false)
    var position: Int = 0,
    /**
     * How this step is settled: observed by the system, or declared by the hire.
     *
     * Reuses [Rigor] rather than introducing a parallel vocabulary, which also means
     * [Rigor.ATTESTED] exists as a slot without being built — role tracks already has a real
     * `Attestation` (a named colleague, never the hire, enforced in the service *and* a DB check),
     * so if "IT granted access" ever wants a genuine confirmer the mechanism is there.
     *
     * A0 only creates [Rigor.DECLARED] steps; derivation for [Rigor.OBSERVED] arrives in A1.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settled_by", nullable = false)
    var settledBy: Rigor = Rigor.DECLARED,
    /**
     * Who authored this step.
     *
     * Nothing generates arrival steps — account creation is a fact about a company, not something
     * derivable from a corpus — so every row is `PM` today. The field mirrors [Competency] and
     * `ModulePage` so that if generation ever arrives it cannot silently overwrite somebody's
     * wording, which is the failure S2 exists to prevent.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var provenance: ContentProvenance = ContentProvenance.PM,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
)
