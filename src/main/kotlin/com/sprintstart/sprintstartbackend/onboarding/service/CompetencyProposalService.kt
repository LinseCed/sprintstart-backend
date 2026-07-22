package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveEdgeSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AiProgressEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.GraphProposalOutcome
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyProposal
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCompetency
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.ApproveGraphBatchResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyEdgeProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.GenerateCompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.ProposedCompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
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
 * Orchestrates AI-proposed competency graph generation and PM review.
 *
 * Mirrors [BlueprintService]'s proposal-only relationship with the AI service, adapted to
 * per-item review: a generation run produces many independently reviewable
 * [CompetencyProposal]/[CompetencyEdgeProposal] rows rather than one whole-resource proposal, so
 * a PM can approve some nodes/edges from a run and reject others. Approving a proposal creates a
 * real [com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency]/
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge] and records the
 * change via [CompetencyGraphVersionService] -- this is the first real production trigger for
 * that machinery beyond the dev seeder.
 *
 * Known limitation: the AI service only dedupes proposals against the *active* live graph, not
 * against other still-[ProposalStatus.PROPOSED] rows from an earlier run. Calling [generate]
 * again before a PM has decided on a prior run's proposals can create duplicate PROPOSED rows.
 * Not solved here -- documented rather than silently worked around.
 */
@Service
@Suppress("TooManyFunctions")
class CompetencyProposalService(
    private val onboardingAiClient: OnboardingAiClient,
    private val competencyRepository: CompetencyRepository,
    private val competencyEdgeRepository: CompetencyEdgeRepository,
    private val competencyProposalRepository: CompetencyProposalRepository,
    private val competencyEdgeProposalRepository: CompetencyEdgeProposalRepository,
    private val competencyGraphVersionService: CompetencyGraphVersionService,
    private val json: Json,
    transactionManager: PlatformTransactionManager,
) {
    // The AI call is a long-running suspend operation, so it must not run inside a
    // transaction (a DB connection would be pinned for its whole duration) -- same
    // reasoning as BlueprintService.
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Triggers AI competency graph proposal generation and persists the results as PROPOSED.
     *
     * The current live graph is sent to the stateless AI service so it can dedupe against what
     * already exists, along with the most recently proposed fingerprint so an unchanged corpus
     * short-circuits to `unchanged`. The AI call runs outside any transaction.
     *
     * @return Summary of how many competencies/edges were newly proposed.
     */
    suspend fun generate(): GenerateCompetencyGraphResponse {
        val (activeCompetencies, activeEdges, lastFingerprint) = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadActiveState() }!!
        }
        val outcome = onboardingAiClient.proposeCompetencyGraph(
            activeCompetencies = activeCompetencies,
            activeEdges = activeEdges,
            lastFingerprint = lastFingerprint,
        )
        val persisted = withContext(Dispatchers.IO) {
            txTemplate.execute { persistProposals(outcome) }!!
        }
        return GenerateCompetencyGraphResponse(
            status = outcome.status,
            competenciesProposed = persisted.competenciesPersisted,
            edgesProposed = persisted.edgesPersisted,
            notes = outcome.notes + persisted.skipped,
        )
    }

    /**
     * The streaming twin of [generate].
     *
     * Relays the AI's `stage`/`item` events so a PM watches the graph assemble (each grounded
     * competency then each accepted edge as an `item`), and on the terminal `done` persists the
     * proposals through the very same [persistProposals] the sync path uses — so the stored result
     * equals the non-streaming path (invariant 2). An item the backend re-gates away on persist
     * (already a live node/edge) is announced as a `warning` before the `done`, never silently
     * dropped (invariant 1). A stream failure becomes a synthesised terminal `error`.
     */
    suspend fun streamGenerate(): Flow<AiProgressEvent> {
        val active = withContext(Dispatchers.IO) { readTxTemplate.execute { loadActiveState() }!! }
        return flow {
            onboardingAiClient
                .streamCompetencyGraph(
                    activeCompetencies = active.competencies,
                    activeEdges = active.edges,
                    lastFingerprint = active.lastFingerprint,
                ).collect { event ->
                    if (event.type == AiProgressEvent.DONE) {
                        decodeOutcome(event)?.let { outcome ->
                            val persisted = withContext(Dispatchers.IO) {
                                txTemplate.execute { persistProposals(outcome) }!!
                            }
                            persisted.skipped.forEach { emit(AiProgressEvent.warning(GRAPH, it)) }
                        }
                    }
                    emit(event)
                }
        }.catch { cause ->
            logger.warn("Competency graph proposal stream failed: {}", cause.message)
            emit(AiProgressEvent.error(GRAPH, "Competency graph generation is temporarily unavailable"))
        }
    }

    private fun decodeOutcome(event: AiProgressEvent): GraphProposalOutcome? =
        event.result?.let {
            runCatching { json.decodeFromJsonElement<GraphProposalOutcome>(it) }
                .onFailure { e -> logger.warn("Could not decode streamed graph result: {}", e.message) }
                .getOrNull()
        }

    /**
     * Persists the outcome's competencies/edges as PROPOSED rows, re-applying the backend's own
     * gate: an element already present in the *live* graph is not re-proposed (the AI dedupes
     * against the active graph the caller sends, but this is the authoritative check at write time).
     * Returns how many of each landed and a human note per skipped element, so the streaming path
     * can surface each skip as a `warning`.
     */
    private fun persistProposals(outcome: GraphProposalOutcome): PersistResult {
        val fingerprint = outcome.provenance?.corpusFingerprint
        val skipped = mutableListOf<String>()
        var competencies = 0
        outcome.competencies.forEach { proposed ->
            if (competencyRepository.existsByKey(proposed.key)) {
                skipped.add("${proposed.label} (${proposed.key}) is already in the graph — not re-proposed")
                return@forEach
            }
            competencyProposalRepository.save(
                CompetencyProposal(
                    key = proposed.key,
                    label = proposed.label,
                    description = proposed.description.takeIf { it.isNotBlank() },
                    kind = CompetencyKind.valueOf(proposed.kind),
                    repoRef = proposed.repoRef,
                    corpusFingerprint = fingerprint,
                ),
            )
            competencies++
        }
        var edges = 0
        outcome.edges.forEach { proposed ->
            val kind = EdgeKind.valueOf(proposed.kind)
            if (competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(proposed.fromKey, proposed.toKey, kind)) {
                skipped.add("${proposed.fromKey} → ${proposed.toKey} is already in the graph — not re-proposed")
                return@forEach
            }
            competencyEdgeProposalRepository.save(
                CompetencyEdgeProposal(
                    fromKey = proposed.fromKey,
                    toKey = proposed.toKey,
                    kind = kind,
                    rationale = proposed.rationale.takeIf { it.isNotBlank() },
                    corpusFingerprint = fingerprint,
                ),
            )
            edges++
        }
        return PersistResult(competencies, edges, skipped)
    }

    private data class PersistResult(
        val competenciesPersisted: Int,
        val edgesPersisted: Int,
        val skipped: List<String>,
    )

    private data class ActiveState(
        val competencies: List<ActiveCompetencySchema>,
        val edges: List<ActiveEdgeSchema>,
        val lastFingerprint: String?,
    )

    private fun loadActiveState(): ActiveState {
        val competencies = competencyRepository.findAll().map {
            ActiveCompetencySchema(
                key = it.key,
                label = it.label,
                description = it.description ?: "",
                kind = it.kind.name,
                repoRef = it.repoRef,
            )
        }
        val edges = competencyEdgeRepository.findAll().map {
            ActiveEdgeSchema(fromKey = it.fromKey, toKey = it.toKey, kind = it.kind.name)
        }
        // The most recently proposed fingerprint, regardless of that proposal's decision --
        // idempotency tracks "what corpus did we last ask about", not approval state.
        val lastFingerprint = competencyProposalRepository.findTopByOrderByCreatedAtDesc()?.corpusFingerprint
            ?: competencyEdgeProposalRepository.findTopByOrderByCreatedAtDesc()?.corpusFingerprint
        return ActiveState(competencies, edges, lastFingerprint)
    }

    /**
     * Returns the competencies and edges currently awaiting PM review.
     */
    @Transactional(readOnly = true)
    fun listProposed(): ProposedCompetencyGraphResponse =
        ProposedCompetencyGraphResponse(
            competencies = competencyProposalRepository
                .findAllByStatus(ProposalStatus.PROPOSED)
                .map { it.toResponse() },
            edges = competencyEdgeProposalRepository
                .findAllByStatus(ProposalStatus.PROPOSED)
                .map { it.toResponse() },
        )

    /**
     * Approves a proposed competency, creating a real
     * [com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency] and recording the
     * change.
     *
     * @throws ResponseStatusException 404 if no PROPOSED competency proposal matches [id].
     */
    @Transactional
    fun approveCompetency(id: UUID): CompetencyProposalResponse {
        val proposal = findPendingCompetencyProposal(id)
        competencyRepository.save(proposal.toCompetency())
        competencyGraphVersionService.recordNodeAdded(proposal.key)
        competencyGraphVersionService.bump()
        proposal.status = ProposalStatus.APPROVED
        proposal.decidedAt = Instant.now()
        return proposal.toResponse()
    }

    /**
     * Rejects a proposed competency, archiving it without touching the live graph.
     *
     * @throws ResponseStatusException 404 if no PROPOSED competency proposal matches [id].
     */
    @Transactional
    fun rejectCompetency(id: UUID, reason: String?): CompetencyProposalResponse {
        val proposal = findPendingCompetencyProposal(id)
        proposal.status = ProposalStatus.REJECTED
        proposal.decidedAt = Instant.now()
        proposal.rejectionReason = reason
        return proposal.toResponse()
    }

    /**
     * Approves a proposed edge, creating a real
     * [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge] and recording
     * the change.
     *
     * @throws ResponseStatusException 404 if no PROPOSED edge proposal matches [id]; 409 if
     * either endpoint is not yet a live competency (its own proposal hasn't been approved).
     */
    @Transactional
    fun approveEdge(id: UUID): CompetencyEdgeProposalResponse {
        val proposal = findPendingEdgeProposal(id)
        if (!competencyRepository.existsByKey(proposal.fromKey) || !competencyRepository.existsByKey(proposal.toKey)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot approve edge ${proposal.fromKey} -> ${proposal.toKey}: both endpoints must already " +
                    "be live competencies",
            )
        }
        competencyEdgeRepository.save(proposal.toCompetencyEdge())
        competencyGraphVersionService.recordEdgeAdded(proposal.fromKey, proposal.toKey, proposal.kind)
        competencyGraphVersionService.bump()
        proposal.status = ProposalStatus.APPROVED
        proposal.decidedAt = Instant.now()
        return proposal.toResponse()
    }

    /**
     * Rejects a proposed edge, archiving it without touching the live graph.
     *
     * @throws ResponseStatusException 404 if no PROPOSED edge proposal matches [id].
     */
    @Transactional
    fun rejectEdge(id: UUID, reason: String?): CompetencyEdgeProposalResponse {
        val proposal = findPendingEdgeProposal(id)
        proposal.status = ProposalStatus.REJECTED
        proposal.decidedAt = Instant.now()
        proposal.rejectionReason = reason
        return proposal.toResponse()
    }

    /**
     * Approves a set of node and edge proposals as a single graph version.
     *
     * Exists because approving them one at a time is not equivalent. Each single approval bumps
     * its own version, so an edge into a node approved a moment earlier is an edge into an
     * *already-existing* node -- which [GraphChangeClassifier] correctly calls STRUCTURAL and
     * [EffectiveGraphResolver] then holds back until the hire's next session start. The hire sees
     * the node arrive as an orphan `AVAILABLE` node with no prerequisites, starts it, and can
     * find it re-locked once the edges land. Approved together, the node counts as newly
     * introduced in the same batch, the whole subgraph classifies ADDITIVE, and it appears
     * already wired.
     *
     * Nodes are created before edges within the batch so [approveEdge]'s endpoint check passes
     * for edges whose endpoints are approved in the same call.
     *
     * @throws ResponseStatusException 404 if any id has no proposal; 409 if any proposal is no
     * longer PROPOSED, or an edge endpoint is not live and not in this batch. The whole batch
     * rolls back -- a partially applied batch is exactly the split version this exists to avoid.
     */
    @Transactional
    fun approveBatch(competencyProposalIds: List<UUID>, edgeProposalIds: List<UUID>): ApproveGraphBatchResponse {
        val competencyProposals = competencyProposalIds.map { findPendingCompetencyProposal(it) }
        val edgeProposals = edgeProposalIds.map { findPendingEdgeProposal(it) }

        competencyProposals.forEach { proposal ->
            competencyRepository.save(proposal.toCompetency())
            competencyGraphVersionService.recordNodeAdded(proposal.key)
            proposal.status = ProposalStatus.APPROVED
            proposal.decidedAt = Instant.now()
        }
        edgeProposals.forEach { proposal ->
            if (!competencyRepository.existsByKey(proposal.fromKey) ||
                !competencyRepository.existsByKey(proposal.toKey)
            ) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot approve edge ${proposal.fromKey} -> ${proposal.toKey}: both endpoints must be live " +
                        "competencies or approved in the same batch",
                )
            }
            competencyEdgeRepository.save(proposal.toCompetencyEdge())
            competencyGraphVersionService.recordEdgeAdded(proposal.fromKey, proposal.toKey, proposal.kind)
            proposal.status = ProposalStatus.APPROVED
            proposal.decidedAt = Instant.now()
        }

        // One bump for the whole batch -- this is the entire point.
        val version = competencyGraphVersionService.bump()
        return ApproveGraphBatchResponse(
            competenciesApproved = competencyProposals.size,
            edgesApproved = edgeProposals.size,
            graphVersion = version,
        )
    }

    private fun findPendingCompetencyProposal(id: UUID): CompetencyProposal {
        val proposal = competencyProposalRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No competency proposal found with id: $id")
        }
        if (proposal.status != ProposalStatus.PROPOSED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Competency proposal $id is already ${proposal.status}",
            )
        }
        return proposal
    }

    private fun findPendingEdgeProposal(id: UUID): CompetencyEdgeProposal {
        val proposal = competencyEdgeProposalRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No competency edge proposal found with id: $id")
        }
        if (proposal.status != ProposalStatus.PROPOSED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Competency edge proposal $id is already ${proposal.status}",
            )
        }
        return proposal
    }

    private companion object {
        // Matches the AI service's operation name and the frontend consumer's switch.
        const val GRAPH = "competency_graph"
    }
}
