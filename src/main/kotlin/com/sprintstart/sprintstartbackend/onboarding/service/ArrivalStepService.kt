package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContentProvenance
import com.sprintstart.sprintstartbackend.onboarding.external.enums.Rigor
import com.sprintstart.sprintstartbackend.onboarding.model.entity.ArrivalStep
import com.sprintstart.sprintstartbackend.onboarding.model.entity.ArrivalStepState
import com.sprintstart.sprintstartbackend.onboarding.repository.ArrivalStepRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.ArrivalStepStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * One arrival step as it applies to one hire: the shared definition, plus whether they have settled
 * it.
 *
 * [settledAt] and [rigor] are null together and mean "not settled yet", which is a normal state on
 * day one rather than missing data.
 */
data class ResolvedArrivalStep(
    val step: ArrivalStep,
    val settledAt: Instant?,
    val rigor: Rigor?,
) {
    val settled: Boolean get() = settledAt != null
}

/**
 * Owns arrival steps: what they are, who they apply to, and what a given hire has settled.
 *
 * ### Nothing here blocks anything
 *
 * There is deliberately no method that answers "may this hire proceed", because no caller should be
 * able to ask. An outstanding arrival step changes what a hire is *shown*, never what they are
 * *allowed to do* — the ordering-not-blocking decision, which exists because the previous
 * generation of this model gated work behind a placement and stranded people who could have done
 * it. If a future caller wants a gate, that is a design change, not a missing method.
 *
 * ### Uniqueness is enforced here as well as in the database
 *
 * A step's key must be unique within its scope, and company-wide steps carry `project_id = NULL`.
 * **Postgres does not treat two NULLs as conflicting**, so the migration expresses this as two
 * partial unique indexes rather than one composite index — and Hibernate cannot express a partial
 * index at all, so the schema the test suite builds from these entities has neither. Hence the
 * explicit checks below: without them the rule would hold in production and quietly not hold in
 * every test, which is the worse half of that failure. `BoardService` guards its own
 * one-row-per-kind rule the same way and for the same reason.
 *
 * Two audiences share this one model: the hire's read plus confirm, and the authoring behind it.
 * They also share the scoping and uniqueness rules above, so splitting the class would mean either
 * duplicating those or adding a third class to hold them — hence the function-count suppression,
 * which `BoardService` carries for the same shape.
 */
@Suppress("TooManyFunctions")
@Service
class ArrivalStepService(
    private val arrivalStepRepository: ArrivalStepRepository,
    private val arrivalStepStateRepository: ArrivalStepStateRepository,
    private val userApi: UserApi,
) {
    /**
     * [forHire] for a caller identified by their auth subject.
     *
     * @throws ResponseStatusException 404 when the subject resolves to no user.
     */
    @Transactional(readOnly = true)
    fun forCaller(authId: String): List<ResolvedArrivalStep> = forHire(resolveUserId(authId))

    /**
     * [confirm] for a caller identified by their auth subject.
     *
     * @throws ResponseStatusException 404 when the subject resolves to no user, or no such step
     * applies to them.
     */
    @Transactional
    fun confirmForCaller(authId: String, key: String): ResolvedArrivalStep =
        confirm(resolveUserId(authId), key)

    /**
     * Every arrival step that applies to [userId], each carrying whether they have settled it.
     *
     * The list is company-wide steps plus the steps of every project the hire belongs to,
     * deduplicated by [ArrivalStep.key] with a project-scoped definition winning — so a project can
     * sharpen a company step's wording without forking the key its state is stored against.
     *
     * Scoped to *all* the hire's projects rather than one, like the buddy's corpus scoping and
     * unlike the board: arrival is a fact about a person, not about a project, and somebody's
     * GitHub account does not become unsettled because they are looking at a different project.
     *
     * @param userId The hire.
     * @return Their steps, company-scoped first, each ordered by position within its own scope.
     * Empty when nobody has authored any steps, which is a real answer and not an error.
     */
    @Transactional(readOnly = true)
    fun forHire(userId: UUID): List<ResolvedArrivalStep> {
        val projectIds = projectIdsFor(userId)

        val companySteps = arrivalStepRepository.findAllByProjectIdIsNullOrderByPositionAsc()
        val projectSteps =
            if (projectIds.isEmpty()) {
                emptyList()
            } else {
                arrivalStepRepository.findAllByProjectIdInOrderByPositionAsc(projectIds)
            }

        // A project-scoped definition wins the key. Ordering *across* the two scopes is not
        // decided yet -- company steps simply come first -- and A3 owns that question when it
        // makes per-project authoring real.
        val projectKeys = projectSteps.map { it.key }.toSet()
        val steps = companySteps.filterNot { it.key in projectKeys } + projectSteps

        val statesByKey = arrivalStepStateRepository.findAllByUserId(userId).associateBy { it.stepKey }

        return steps.map { step ->
            val state = statesByKey[step.key]
            ResolvedArrivalStep(step = step, settledAt = state?.settledAt, rigor = state?.rigor)
        }
    }

    /**
     * Records that [userId] says they have done the step [key].
     *
     * Idempotent: settling an already-settled step returns what is already there and leaves
     * [ArrivalStepState.settledAt] alone, because the day something happened does not move. This is
     * the same monotonicity the ledger holds to, for the same reason.
     *
     * @param userId The hire confirming.
     * @param key The step's stable key, which must be one that applies to this hire.
     * @return The step with its new state.
     * @throws ResponseStatusException 404 when no step with that key applies to this hire; 400 when
     * the step is settled by observation rather than by the hire.
     */
    @Transactional
    fun confirm(userId: UUID, key: String): ResolvedArrivalStep {
        val resolved =
            forHire(userId).firstOrNull { it.step.key == key }
                ?: throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No arrival step '$key' applies to this user",
                )

        // A step the system settles is not one a hire ticks. Allowing it would let somebody assert
        // a fact the system is meant to observe -- and while the rigor recorded would still be
        // DECLARED, the readout would then show a step as done that no derivation ever confirmed.
        // Nothing is blocked by refusing: an unsettled step never stopped them working.
        if (resolved.step.settledBy != Rigor.DECLARED) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Arrival step '$key' is settled by ${resolved.step.settledBy}, not by you",
            )
        }

        if (resolved.settled) {
            return resolved
        }

        val state =
            arrivalStepStateRepository.save(
                ArrivalStepState(
                    userId = userId,
                    stepKey = key,
                    projectId = resolved.step.projectId,
                    rigor = Rigor.DECLARED,
                ),
            )

        return resolved.copy(settledAt = state.settledAt, rigor = state.rigor)
    }

    /** Every authored step in a scope, for the authoring surface. */
    @Transactional(readOnly = true)
    fun listForAuthoring(projectId: UUID?): List<ArrivalStep> {
        return if (projectId == null) {
            arrivalStepRepository.findAllByProjectIdIsNullOrderByPositionAsc()
        } else {
            arrivalStepRepository.findAllByProjectIdInOrderByPositionAsc(listOf(projectId))
        }
    }

    /**
     * Creates a step.
     *
     * @throws ResponseStatusException 400 when the key is blank or malformed; 409 when a step with
     * that key already exists in the same scope.
     */
    @Transactional
    fun create(
        key: String,
        projectId: UUID?,
        title: String,
        description: String?,
        href: String?,
        position: Int,
        settledBy: Rigor,
    ): ArrivalStep {
        val normalizedKey = normalizeKey(key)
        requireKeyFree(normalizedKey, projectId)

        if (title.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "An arrival step needs a title")
        }

        return arrivalStepRepository.save(
            ArrivalStep(
                key = normalizedKey,
                projectId = projectId,
                title = title.trim(),
                description = description?.trim()?.ifBlank { null },
                href = href?.trim()?.ifBlank { null },
                position = position,
                settledBy = settledBy,
                provenance = ContentProvenance.PM,
            ),
        )
    }

    /**
     * Updates a step's wording, link, ordering or settlement mechanism.
     *
     * The key is **not** updatable. State points at it, so changing it would orphan every hire's
     * record of having done the step while leaving the row looking healthy — a rename must be a
     * delete and a create, where the consequence is at least visible.
     *
     * @throws ResponseStatusException 404 when no such step exists in that scope; 400 on a blank
     * title.
     */
    @Transactional
    fun update(
        key: String,
        projectId: UUID?,
        title: String?,
        description: String?,
        href: String?,
        position: Int?,
        settledBy: Rigor?,
    ): ArrivalStep {
        val step = findOrThrow(key, projectId)

        title?.let {
            if (it.isBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "An arrival step needs a title")
            }
            step.title = it.trim()
        }
        description?.let { step.description = it.trim().ifBlank { null } }
        href?.let { step.href = it.trim().ifBlank { null } }
        position?.let { step.position = it }
        settledBy?.let { step.settledBy = it }
        step.provenance = ContentProvenance.PM

        return arrivalStepRepository.save(step)
    }

    /**
     * Applies a whole ordering at once.
     *
     * Takes the complete list rather than a from/to pair, so two people reordering concurrently
     * cannot interleave into an order neither of them chose — the rule board checklists already
     * follow.
     *
     * @throws ResponseStatusException 404 when a key in [orderedKeys] is not a step in that scope.
     */
    @Transactional
    fun reorder(projectId: UUID?, orderedKeys: List<String>): List<ArrivalStep> {
        val steps = listForAuthoring(projectId).associateBy { it.key }

        orderedKeys.forEachIndexed { index, key ->
            val step =
                steps[key]
                    ?: throw ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No arrival step '$key' in this scope",
                    )
            step.position = index
        }

        return arrivalStepRepository.saveAll(steps.values.sortedBy { it.position })
    }

    /**
     * Deletes a step definition. **Hires' state survives**, by design.
     *
     * [ArrivalStepState] is keyed by the step's key rather than by a foreign key, so removing a
     * definition removes it from everybody's list without destroying the record that somebody did
     * it — and re-adding the same key restores those records. That is the property that has made
     * five deletions safe in this codebase, and `ArrivalStepServiceTest` pins it.
     *
     * @throws ResponseStatusException 404 when no such step exists in that scope.
     */
    @Transactional
    fun delete(key: String, projectId: UUID?) {
        arrivalStepRepository.delete(findOrThrow(key, projectId))
    }

    private fun findOrThrow(key: String, projectId: UUID?): ArrivalStep {
        val step =
            if (projectId == null) {
                arrivalStepRepository.findByKeyAndProjectIdIsNull(key)
            } else {
                arrivalStepRepository.findByKeyAndProjectId(key, projectId)
            }

        return step
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No arrival step '$key' in this scope")
    }

    private fun requireKeyFree(key: String, projectId: UUID?) {
        val taken =
            if (projectId == null) {
                arrivalStepRepository.existsByKeyAndProjectIdIsNull(key)
            } else {
                arrivalStepRepository.existsByKeyAndProjectId(key, projectId)
            }

        if (taken) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An arrival step '$key' already exists in this scope",
            )
        }
    }

    private fun normalizeKey(key: String): String {
        val normalized = key.trim().lowercase()

        if (!KEY.matches(normalized)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "'$key' is not a valid arrival step key (lower-case letters, digits, '-' and '_')",
            )
        }

        return normalized
    }

    private fun resolveUserId(authId: String): UUID =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }

    private fun projectIdsFor(userId: UUID): List<UUID> =
        userApi
            .getUsersByIds(listOf(userId))
            .firstOrNull()
            ?.projects
            .orEmpty()
            .map { it.projectId }

    private companion object {
        val KEY = Regex("^[a-z\\d][a-z\\d_-]{0,63}$")
    }
}
