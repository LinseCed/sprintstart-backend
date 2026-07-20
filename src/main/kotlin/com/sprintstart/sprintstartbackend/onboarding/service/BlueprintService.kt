package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BaselineSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintOrigin
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintRequirement
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toSchema
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintOutcomeResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.BlueprintResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.GenerateBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.ProposedBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint.VersionListResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintCompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
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

/**
 * Owns the mandatory baseline: *which competencies must everyone in a scope reach, and to what
 * level*. The AI proposes the selection from the corpus, a PM approves it -- generation never
 * activates anything.
 */
@Service
class BlueprintService(
    private val onboardingAiClient: OnboardingAiClient,
    private val blueprintRepository: BlueprintRepository,
    private val blueprintCompetencyRepository: BlueprintCompetencyRepository,
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
     * Triggers AI baseline generation for the given scopes and persists the results.
     *
     * The current active baselines are loaded and sent to the stateless AI service so it can
     * number versions and skip an unchanged corpus. Only `created`/`updated` outcomes are
     * persisted, and only as PROPOSED proposals awaiting PM approval — the current ACTIVE for a
     * scope is never archived or replaced here. `escalated`/`unchanged`/`skipped` outcomes are not
     * persisted. The AI call runs outside any transaction to avoid pinning a DB connection for its
     * duration.
     *
     * @param scopes The scopes to (re)generate, or `null` to refresh all known scopes.
     * @return The per-scope generation outcomes.
     */
    suspend fun generateBlueprints(scopes: List<String>?): GenerateBlueprintsResponse {
        // The AI service is stateless: pass it the current active baselines so it can number
        // versions and skip an unchanged corpus, plus the live competency graph -- the set it
        // selects the baseline from.
        val (active, competencies) = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadActiveState(scopes) }
                ?: (emptyList<BaselineSchema>() to emptyList())
        }
        val response = onboardingAiClient.generateBlueprints(scopes, active, competencies)
        withContext(Dispatchers.IO) {
            txTemplate.executeWithoutResult {
                for (outcome in response.outcomes) {
                    val generated = outcome.blueprint
                    // Only freshly created/updated baselines are proposed.
                    // `escalated` means a protected entry was violated and must be
                    // reviewed — it is never silently proposed — and
                    // `unchanged`/`skipped` carry no baseline.
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
     * Persists an AI-generated baseline as a PROPOSED proposal awaiting PM approval.
     *
     * Proposal-only is the invariant of the mandatory baseline layer: generation never activates a
     * baseline and never touches the current ACTIVE.
     *
     * Entries naming a competency that does not exist in the graph are dropped rather than
     * persisted: a baseline entry is a *selection over the graph*, so a key with no node behind it
     * would be a target nothing can teach, and it would be invisible on the review surface anyway.
     * The AI validates against the same catalog; this is the backend not trusting that.
     */
    private fun propose(generated: BaselineSchema) {
        val knownKeys = competencyRepository
            .findAllByKeyIn(generated.competencies.map { it.competencyKey })
            .map { it.key }
            .toSet()

        val blueprint = Blueprint(
            scope = generated.scope,
            version = generated.version,
            status = BlueprintStatus.PROPOSED,
            origin = BlueprintOrigin.AI_PROPOSED,
            corpusFingerprint = generated.provenance?.corpusFingerprint,
        )
        generated.competencies
            .filter { it.competencyKey in knownKeys }
            .forEachIndexed { index, entry ->
                blueprint.competencies.add(
                    BlueprintCompetency(
                        blueprint = blueprint,
                        competencyKey = entry.competencyKey,
                        targetLevel = entry.targetLevel?.takeIf { it in VALID_TARGET_LEVELS },
                        requirement = BlueprintRequirement.fromWire(entry.requirement),
                        invariant = entry.invariant,
                        rationale = entry.rationale.takeIf { it.isNotBlank() },
                        position = index,
                    ),
                )
            }
        val dropped = generated.competencies.size - blueprint.competencies.size
        if (dropped > 0) {
            logger.info("Dropped {} proposed baseline entries for unknown competencies", dropped)
        }
        blueprintRepository.save(blueprint)
    }

    /**
     * Loads the stateless-AI generation context in a single read transaction: the ACTIVE baselines
     * for the given [scopes] (or all scopes when `null`) as the wire schema, plus the live
     * competency graph the AI selects from. A key outside that catalog is discarded on both sides,
     * so the whole graph is sent.
     *
     * @param scopes A list of scopes, or `null` for all scopes.
     * @return The active baselines and the live competency catalog.
     */
    private fun loadActiveState(
        scopes: List<String>?,
    ): Pair<List<BaselineSchema>, List<ActiveCompetencySchema>> {
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
     * @param scope The baseline scope to list versions for.
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
     * Restores a previously archived baseline [version] as the new ACTIVE for [scope].
     *
     * The current ACTIVE is archived and the archived version's selection is copied into a new
     * ACTIVE baseline.
     *
     * @param scope The baseline scope to roll back.
     * @param version The archived version identifier to restore.
     * @return The restored, now-active baseline.
     * @throws ResponseStatusException 404 if no archived baseline matches [scope]/[version].
     */
    @Transactional
    fun rollback(scope: String, version: String): BlueprintResponse {
        val archived = blueprintRepository.findByScopeAndStatusAndVersion(scope, BlueprintStatus.ARCHIVED, version)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No version $version for scope: $scope")
        blueprintRepository
            .findByScopeAndStatus(scope, BlueprintStatus.ACTIVE)
            ?.let { it.status = BlueprintStatus.ARCHIVED }
        val newBlueprint = Blueprint(
            projectId = archived.projectId,
            scope = archived.scope,
            version = archived.version,
            status = BlueprintStatus.ACTIVE,
            corpusFingerprint = archived.corpusFingerprint,
        )
        archived.competencies.forEach { entry ->
            newBlueprint.competencies.add(
                BlueprintCompetency(
                    blueprint = newBlueprint,
                    competencyKey = entry.competencyKey,
                    targetLevel = entry.targetLevel,
                    requirement = entry.requirement,
                    invariant = entry.invariant,
                    rationale = entry.rationale,
                    position = entry.position,
                    status = entry.status,
                    decidedAt = entry.decidedAt,
                    rejectionReason = entry.rejectionReason,
                ),
            )
        }
        blueprintRepository.save(newBlueprint)
        blueprintRepository.delete(archived)
        return newBlueprint.toResponse(competencyRepository.competenciesFor(listOf(newBlueprint)))
    }

    /**
     * Returns the baselines currently awaiting review, optionally filtered by [scope].
     *
     * @param scope When non-null, restricts the result to proposals for that scope.
     * @return The PROPOSED baselines as API responses.
     */
    @Transactional(readOnly = true)
    fun listProposed(scope: String? = null): ProposedBlueprintsResponse {
        val proposed = if (scope != null) {
            blueprintRepository.findAllByScopeAndStatus(scope, BlueprintStatus.PROPOSED)
        } else {
            blueprintRepository.findAllByStatus(BlueprintStatus.PROPOSED)
        }
        val competencies = competencyRepository.competenciesFor(proposed)
        return ProposedBlueprintsResponse(blueprints = proposed.map { it.toResponse(competencies) })
    }

    /**
     * Approves a proposed baseline [version] for [scope], making it the new ACTIVE baseline.
     *
     * The current ACTIVE for the scope (if any) is archived and the proposal is flipped to ACTIVE
     * in place. This is the only path by which a baseline becomes live; nothing is activated
     * without this explicit PM action, and activating one changes which nodes land on the paths of
     * everyone in the scope.
     *
     * @param scope The baseline scope to approve within.
     * @param version The proposed version to approve.
     * @return The now-active baseline.
     * @throws ResponseStatusException 404 if no PROPOSED baseline matches [scope]/[version].
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
        return proposed.toResponse(competencyRepository.competenciesFor(listOf(proposed)))
    }

    /**
     * Rejects a proposed baseline [version] for [scope], archiving it without activation.
     *
     * The current ACTIVE baseline is left untouched. The optional [reason] is logged for
     * traceability but not persisted — there is no rejection-reason column on the blueprint.
     *
     * @param scope The baseline scope to reject within.
     * @param version The proposed version to reject.
     * @param reason Optional human-readable rejection reason (logged, not persisted).
     * @return The archived baseline.
     * @throws ResponseStatusException 404 if no PROPOSED baseline matches [scope]/[version].
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
        return proposed.toResponse(competencyRepository.competenciesFor(listOf(proposed)))
    }

    /**
     * Approves one entry within a proposal, independent of the whole baseline's own
     * DRAFT/PROPOSED/ACTIVE decision -- this is per-entry curation, not activation.
     *
     * @throws ResponseStatusException 404 if no entry matches [id]; 409 if it was already decided.
     */
    @Transactional
    fun approveCompetency(id: UUID): BlueprintCompetencyResponse {
        val entry = findPendingCompetency(id)
        entry.status = ProposalStatus.APPROVED
        entry.decidedAt = Instant.now()
        return entry.toResponse(competencyRepository.findByKey(entry.competencyKey))
    }

    /**
     * Rejects one entry within a proposal: that competency is no longer part of the baseline. The
     * entry stays on the blueprint as an audit trail (same convention as
     * [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal]) but is
     * excluded from everything downstream.
     *
     * @throws ResponseStatusException 404 if no entry matches [id]; 409 if it was already decided,
     * or if the entry is [BlueprintCompetency.invariant] -- an invariant entry is protected from
     * removal on the AI generation side already, so rejecting it here would defeat that guarantee.
     */
    @Transactional
    fun rejectCompetency(id: UUID, reason: String? = null): BlueprintCompetencyResponse {
        val entry = findPendingCompetency(id)
        if (entry.invariant) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cannot reject invariant baseline entry: $id")
        }
        entry.status = ProposalStatus.REJECTED
        entry.decidedAt = Instant.now()
        entry.rejectionReason = reason
        return entry.toResponse(competencyRepository.findByKey(entry.competencyKey))
    }

    private fun findPendingCompetency(id: UUID): BlueprintCompetency {
        val entry = blueprintCompetencyRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No blueprint competency found with id: $id")
        }
        if (entry.status != ProposalStatus.PROPOSED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Blueprint competency $id is already ${entry.status}")
        }
        return entry
    }

    private companion object {
        val PROPOSABLE_STATUSES = setOf("created", "updated")

        // Ledger/target levels are ranks 1..4; anything else is not a bar, so an override outside
        // the range falls back to the competency's own target level rather than being stored.
        val VALID_TARGET_LEVELS = 1..4
    }
}

/** Loads the graph nodes the given baselines select, keyed by competency key, in one query. */
private fun CompetencyRepository.competenciesFor(blueprints: List<Blueprint>): Map<String, Competency> {
    val keys = blueprints.flatMap { it.competencies }.map { it.competencyKey }.toSet()
    if (keys.isEmpty()) return emptyMap()
    return findAllByKeyIn(keys).associateBy { it.key }
}
