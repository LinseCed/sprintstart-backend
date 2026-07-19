package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.GeneratedBlueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintOrigin
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStep
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toSchema
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintOutcomeResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.GenerateBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.ProposedBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.VersionListResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintStepRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class BlueprintService(
    private val onboardingAiClient: OnboardingAiClient,
    private val blueprintRepository: BlueprintRepository,
    private val blueprintStepRepository: BlueprintStepRepository,
    private val competencyRepository: CompetencyRepository,
    transactionManager: PlatformTransactionManager,
) {
    // The AI call is a long-running suspend operation, so it must not run inside
    // a transaction (a DB connection would be pinned for its whole duration).
    // The surrounding DB reads/writes use explicit transactions on Dispatchers.IO
    // so the JPA session stays bound to the thread doing the work.
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate =
        TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Triggers AI blueprint generation for the given scopes and persists the results.
     *
     * The current active blueprints are loaded and sent to the stateless AI service so
     * it can number versions and skip an unchanged corpus. Only `created`/`updated`
     * outcomes are persisted, and only as PROPOSED proposals awaiting PM approval — the
     * current ACTIVE for a scope is never archived or replaced here. `escalated`/
     * `unchanged`/`skipped` outcomes are not persisted. The AI call runs outside any
     * transaction to avoid pinning a DB connection for its duration.
     *
     * @param scopes The scopes to (re)generate, or `null` to refresh all known scopes.
     * @return The per-scope generation outcomes.
     */
    suspend fun generateBlueprints(scopes: List<String>?): GenerateBlueprintsResponse {
        // The AI service is stateless: pass it the current active blueprints so
        // it can number versions and skip an unchanged corpus, plus the live competency
        // graph so it can tag each generated step with a competency key (blueprint->target bridge).
        val (active, competencies) = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadActiveState(scopes) }
                ?: (emptyList<BlueprintSchema>() to emptyList())
        }
        val response = onboardingAiClient.generateBlueprints(scopes, active, competencies)
        withContext(Dispatchers.IO) {
            txTemplate.executeWithoutResult {
                for (outcome in response.outcomes) {
                    val generated = outcome.blueprint
                    // Only freshly created/updated blueprints are proposed.
                    // `escalated` means a protected step was violated and must be
                    // reviewed — it is never silently proposed — and
                    // `unchanged`/`skipped` carry no blueprint.
                    if (generated != null && outcome.status in PROPOSABLE_STATUSES) {
                        propose(generated)
                    }
                }
            }
        }
        return GenerateBlueprintsResponse(
            outcomes = response.outcomes.map { outcome ->
                BlueprintOutcomeResponse(
                    scope = outcome.scope,
                    status = outcome.status,
                    message = outcome.notes.firstOrNull(),
                )
            },
        )
    }

    /**
     * Persists an AI-generated blueprint as a PROPOSED proposal awaiting PM approval.
     *
     * Proposal-only is the invariant of the mandatory baseline layer: generation never activates a
     * blueprint and never touches the current ACTIVE. The proposal is stored with origin
     * [BlueprintOrigin.AI_PROPOSED] and only becomes live once [approve] is called.
     *
     * @param generated [GeneratedBlueprint] The generated blueprint.
     */
    private fun propose(generated: GeneratedBlueprint) {
        val blueprint = Blueprint(
            scope = generated.scope,
            version = generated.version,
            status = BlueprintStatus.PROPOSED,
            origin = BlueprintOrigin.AI_PROPOSED,
            corpusFingerprint = generated.provenance?.corpusFingerprint,
        )
        generated.steps.forEachIndexed { index, step ->
            blueprint.steps.add(
                BlueprintStep(
                    blueprint = blueprint,
                    stepId = step.id,
                    title = step.title,
                    description = step.description?.takeIf { it.isNotBlank() },
                    minExperience = step.minExperience,
                    audience = step.audience.joinToString(","),
                    position = index,
                    requirement = step.requirement,
                    invariant = step.invariant,
                    competencyKey = step.competencyKey,
                ),
            )
        }
        blueprintRepository.save(blueprint)
    }

    /**
     * Loads the stateless-AI generation context in a single read transaction: the ACTIVE
     * blueprints for the given [scopes] (or all scopes when `null`) as the wire schema, plus the
     * live competency graph the AI tags generated steps against (the blueprint->target bridge).
     * The AI discards any competency key not in the supplied catalog, so the whole graph is sent.
     *
     * @param scopes A list of scopes, or `null` for all scopes.
     * @return The active blueprints and the live competency catalog.
     */
    private fun loadActiveState(
        scopes: List<String>?,
    ): Pair<List<BlueprintSchema>, List<ActiveCompetencySchema>> {
        val active = scopes?.mapNotNull {
            blueprintRepository.findByScopeAndStatus(it, BlueprintStatus.ACTIVE)
        }
            ?: blueprintRepository.findAllByStatus(BlueprintStatus.ACTIVE)
        val competencies = competencyRepository.findAll().map {
            ActiveCompetencySchema(
                key = it.key,
                label = it.label,
                description = it.description ?: "",
                kind = it.kind.name,
                repoRef = it.repoRef,
            )
        }
        return active.map { it.toSchema() } to competencies
    }

    /**
     * Returns the archived (rollback-able) version identifiers retained for [scope].
     *
     * @param scope The blueprint scope to list versions for.
     * @return The scope and its archived version identifiers.
     */
    @Transactional(readOnly = true)
    fun listVersions(scope: String): VersionListResponse {
        val versions = blueprintRepository
            .findAllByScopeAndStatus(scope, BlueprintStatus.ARCHIVED)
            .map { it.version }
        if (versions.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No blueprint found for scope: $scope")
        }
        return VersionListResponse(scope = scope, versions = versions)
    }

    /**
     * Restores a previously archived blueprint [version] as the new ACTIVE for [scope].
     *
     * The current ACTIVE is archived and the archived version is copied into a new
     * ACTIVE blueprint.
     *
     * @param scope The blueprint scope to roll back.
     * @param version The archived version identifier to restore.
     * @return The restored, now-active blueprint.
     * @throws ResponseStatusException 404 if no archived blueprint matches [scope]/[version].
     */
    @Transactional
    fun rollback(scope: String, version: String): BlueprintResponse {
        val archived = blueprintRepository.findByScopeAndStatusAndVersion(scope, BlueprintStatus.ARCHIVED, version)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No version $version for scope: $scope")
        blueprintRepository
            .findByScopeAndStatus(scope, BlueprintStatus.ACTIVE)
            ?.let { it.status = BlueprintStatus.ARCHIVED }
        val newBlueprint = Blueprint(
            scope = archived.scope,
            version = archived.version,
            status = BlueprintStatus.ACTIVE,
            corpusFingerprint = archived.corpusFingerprint,
        )
        archived.steps.forEach { step ->
            newBlueprint.steps.add(
                BlueprintStep(
                    blueprint = newBlueprint,
                    stepId = step.stepId,
                    title = step.title,
                    description = step.description,
                    minExperience = step.minExperience,
                    audience = step.audience,
                    position = step.position,
                    requirement = step.requirement,
                    invariant = step.invariant,
                    competencyKey = step.competencyKey,
                ),
            )
        }
        blueprintRepository.save(newBlueprint)
        blueprintRepository.delete(archived)
        return newBlueprint.toResponse()
    }

    /**
     * Returns the blueprints currently awaiting review, optionally filtered by [scope].
     *
     * @param scope When non-null, restricts the result to proposals for that scope.
     * @return The PROPOSED blueprints as API responses.
     */
    @Transactional(readOnly = true)
    fun listProposed(scope: String? = null): ProposedBlueprintsResponse {
        val proposed = if (scope != null) {
            blueprintRepository.findAllByScopeAndStatus(scope, BlueprintStatus.PROPOSED)
        } else {
            blueprintRepository.findAllByStatus(BlueprintStatus.PROPOSED)
        }
        return ProposedBlueprintsResponse(blueprints = proposed.map { it.toResponse() })
    }

    /**
     * Approves a proposed blueprint [version] for [scope], making it the new ACTIVE baseline.
     *
     * The current ACTIVE for the scope (if any) is archived and the proposal is flipped to ACTIVE
     * in place. This is the only path by which a blueprint becomes live; nothing is activated
     * without this explicit PM action.
     *
     * @param scope The blueprint scope to approve within.
     * @param version The proposed version to approve.
     * @return The now-active blueprint.
     * @throws ResponseStatusException 404 if no PROPOSED blueprint matches [scope]/[version].
     */
    @Transactional
    fun approve(scope: String, version: String): BlueprintResponse {
        val proposed = blueprintRepository.findByScopeAndStatusAndVersion(scope, BlueprintStatus.PROPOSED, version)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No proposed blueprint version $version for scope: $scope",
            )
        blueprintRepository
            .findByScopeAndStatus(scope, BlueprintStatus.ACTIVE)
            ?.let { it.status = BlueprintStatus.ARCHIVED }
        proposed.status = BlueprintStatus.ACTIVE
        return proposed.toResponse()
    }

    /**
     * Rejects a proposed blueprint [version] for [scope], archiving it without activation.
     *
     * The current ACTIVE baseline is left untouched. The optional [reason] is logged for
     * traceability but not persisted — there is no rejection-reason column in this phase.
     *
     * @param scope The blueprint scope to reject within.
     * @param version The proposed version to reject.
     * @param reason Optional human-readable rejection reason (logged, not persisted).
     * @return The archived blueprint.
     * @throws ResponseStatusException 404 if no PROPOSED blueprint matches [scope]/[version].
     */
    @Transactional
    fun reject(scope: String, version: String, reason: String? = null): BlueprintResponse {
        val proposed = blueprintRepository.findByScopeAndStatusAndVersion(scope, BlueprintStatus.PROPOSED, version)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No proposed blueprint version $version for scope: $scope",
            )
        proposed.status = BlueprintStatus.ARCHIVED
        logger.info("Rejected proposed blueprint {} v{} (reason: {})", scope, version, reason ?: "none given")
        return proposed.toResponse()
    }

    /**
     * Approves one [BlueprintStep] within a proposal, independent of the whole blueprint's own
     * DRAFT/PROPOSED/ACTIVE decision -- this is per-step curation, not activation.
     *
     * @throws ResponseStatusException 404 if no step matches [id]; 409 if it was already decided.
     */
    @Transactional
    fun approveStep(id: UUID): BlueprintStepResponse {
        val step = findPendingStep(id)
        step.status = ProposalStatus.APPROVED
        step.decidedAt = Instant.now()
        return step.toResponse()
    }

    /**
     * Rejects one [BlueprintStep] within a proposal. The step stays in the blueprint (audit
     * trail, same convention as [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal])
     * but is excluded from [Blueprint.toSchema]'s output from here on.
     *
     * @throws ResponseStatusException 404 if no step matches [id]; 409 if it was already decided,
     * or if the step is [BlueprintStep.invariant] -- an invariant step is protected from removal
     * by the AI generation side already, so rejecting it here would defeat that guarantee.
     */
    @Transactional
    fun rejectStep(id: UUID, reason: String? = null): BlueprintStepResponse {
        val step = findPendingStep(id)
        if (step.invariant) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cannot reject invariant step: $id")
        }
        step.status = ProposalStatus.REJECTED
        step.decidedAt = Instant.now()
        step.rejectionReason = reason
        return step.toResponse()
    }

    private fun findPendingStep(id: UUID): BlueprintStep {
        val step = blueprintStepRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No blueprint step found with id: $id")
        }
        if (step.status != ProposalStatus.PROPOSED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Blueprint step $id is already ${step.status}")
        }
        return step
    }

    private companion object {
        val PROPOSABLE_STATUSES = setOf("created", "updated")
    }
}
