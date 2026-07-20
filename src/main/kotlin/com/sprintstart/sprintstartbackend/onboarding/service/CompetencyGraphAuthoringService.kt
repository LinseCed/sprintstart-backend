package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.CreateCompetencyEdgeRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.UpdateCompetencyRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyEdgeResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.DeleteCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphChangeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * A PM's direct authoring of the live competency graph: editing a node, removing one, and adding
 * or removing an edge by hand.
 *
 * The counterpart to [CompetencyProposalService], which only offers a binary vote on what the AI
 * generated. A PM who spots a wrong label or a missing prerequisite had, until this service, only
 * two options: accept it wrong, or reject and regenerate the whole graph. That is not
 * "AI proposes, PM owns".
 *
 * ### Every write records a change row
 *
 * [EffectiveGraphResolver] derives visibility purely by replaying [CompetencyGraphChange]
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange] rows, so a
 * write straight to `competencies`/`competency_edges` without a change row produces a permanently
 * invisible ghost. Every mutation here goes through [CompetencyGraphVersionService] and bumps the
 * version, exactly like proposal approval does.
 *
 * ### Removal is recorded, not deleted
 *
 * [deleteCompetency] records `NODE_REMOVED` (plus `EDGE_REMOVED` for every edge touching it) and
 * leaves every row in place. The node stops being replayed into the visible set, so it leaves
 * every hire's path -- but the ledger, the baseline entries and the authored module survive
 * untouched. Nobody un-earns a competency because a PM tidied the graph, and the removal is
 * reversible.
 *
 * ### Known limitation: edits are not deferrable
 *
 * A `NODE_MODIFIED` change classifies STRUCTURAL, but the hold-back does not actually apply to
 * it: the resolver replays change rows to decide *which* keys are visible and then reads content
 * live from the `competencies` table. So an edited label, kind or **target level** reaches every
 * hire immediately regardless of their pin -- and raising a target level can re-lock a node that
 * read as mastered. Deferring an edit would need per-version node content, which
 * `competency_graph_changes` does not carry. Documented rather than implied away.
 */
@Service
class CompetencyGraphAuthoringService(
    private val competencyRepository: CompetencyRepository,
    private val competencyEdgeRepository: CompetencyEdgeRepository,
    private val competencyGraphChangeRepository: CompetencyGraphChangeRepository,
    private val competencyGraphVersionRepository: CompetencyGraphVersionRepository,
    private val competencyGraphVersionService: CompetencyGraphVersionService,
    private val effectiveGraphResolver: EffectiveGraphResolver,
    private val graphTraversalService: GraphTraversalService,
) {
    /**
     * The graph as it stands at the current head -- what a PM is authoring against.
     *
     * Every check in this service resolves against this rather than against
     * `competencyRepository.findAll()`, because soft removal means the tables are a superset of
     * the live graph: a removed node or edge still has its row. Asking the tables "does this
     * exist?" would refuse to re-add something a PM removed, and would let a removed edge veto a
     * new one as a cycle.
     *
     * Resolved with the pin at the head so nothing is held back -- a PM authors against what the
     * graph actually is now, not against any individual hire's deferred view.
     */
    private fun headGraph(): EffectiveGraph {
        val currentVersion = competencyGraphVersionService.currentVersion()
        return effectiveGraphResolver.resolve(
            pinnedVersion = currentVersion,
            currentVersion = currentVersion,
            versionHistory = competencyGraphVersionRepository.findAllByVersionGreaterThanOrderByVersionAsc(0),
            changes = competencyGraphChangeRepository.findAll(),
            allCompetencies = competencyRepository.findAll(),
            allEdges = competencyEdgeRepository.findAll(),
        )
    }

    /**
     * Reads one live competency, so an editor can show what it currently says.
     *
     * The projected path deliberately carries only what a hire's map needs (label, kind, state),
     * not `description`/`targetLevel`/`invariant` -- so without this an edit form would have to
     * start blank on the fields a PM is most likely to be adjusting.
     *
     * @throws ResponseStatusException 404 if no competency has [key], or it was removed.
     */
    @Transactional(readOnly = true)
    fun getCompetency(key: String): CompetencyResponse =
        findVisibleCompetency(key, headGraph()).toAuthoringResponse()

    /**
     * The whole live graph at the head version — what a PM authors against.
     *
     * Until this existed the only way to see the graph as a whole was `GET /me/path`, a hire's
     * projection: filtered to one project's baseline, carrying per-user node state, and resolved
     * at that hire's pin. A PM looking at it saw their own onboarding, and on a project whose
     * baseline selects nothing they saw an empty graph with nothing to edit.
     */
    @Transactional(readOnly = true)
    fun getGraph(): CompetencyGraphResponse {
        val graph = headGraph()
        return CompetencyGraphResponse(
            competencies = graph.competencies.map { it.toAuthoringResponse() },
            edges = graph.edges.map { it.toAuthoringResponse() },
            graphVersion = competencyGraphVersionService.currentVersion(),
        )
    }

    /**
     * Applies a PM's edit to one competency node.
     *
     * Omitted fields are left alone. [key] is not editable -- see [UpdateCompetencyRequest].
     *
     * @throws ResponseStatusException 404 if no competency has [key]; 400 if `targetLevel` is
     * outside 1..4.
     */
    @Transactional
    fun updateCompetency(key: String, request: UpdateCompetencyRequest): CompetencyResponse {
        val competency = findVisibleCompetency(key, headGraph())

        request.targetLevel?.let { level ->
            if (level !in MIN_TARGET_LEVEL..MAX_TARGET_LEVEL) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "targetLevel must be between $MIN_TARGET_LEVEL and $MAX_TARGET_LEVEL, got $level",
                )
            }
            competency.targetLevel = level
        }
        request.label?.let { label ->
            if (label.isBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "label must not be blank")
            }
            competency.label = label.trim()
        }
        // A blank description is how a PM clears one, so it maps to null rather than being rejected.
        request.description?.let { competency.description = it.trim().takeIf(String::isNotBlank) }
        request.kind?.let { competency.kind = it }
        request.invariant?.let { competency.invariant = it }

        competencyRepository.save(competency)
        competencyGraphVersionService.recordNodeModified(key)
        competencyGraphVersionService.bump()
        return competency.toAuthoringResponse()
    }

    /**
     * Removes a competency node and every edge touching it from the visible graph.
     *
     * Nothing is deleted: rows stay, change rows make them invisible. Any competency a hire has
     * already earned stays on their ledger -- graph visibility and earned progress are
     * independent, which is what makes this safe to do to a live graph.
     *
     * @throws ResponseStatusException 404 if no competency has [key].
     */
    @Transactional
    fun deleteCompetency(key: String): DeleteCompetencyResponse {
        val graph = headGraph()
        findVisibleCompetency(key, graph)

        // Edges are recorded as removed too: leaving them behind would keep dangling
        // prerequisites in the replayed edge set, pointing at a node that is no longer there.
        val touchingEdges = graph.edges.filter { it.fromKey == key || it.toKey == key }
        touchingEdges.forEach {
            competencyGraphVersionService.recordEdgeRemoved(it.fromKey, it.toKey, it.kind)
        }
        competencyGraphVersionService.recordNodeRemoved(key)
        val version = competencyGraphVersionService.bump()

        return DeleteCompetencyResponse(key = key, edgesRemoved = touchingEdges.size, graphVersion = version)
    }

    /**
     * Adds a hand-authored edge between two live competencies.
     *
     * @throws ResponseStatusException 404 if either endpoint is not a live competency; 409 if the
     * edge already exists; 400 if it is a self-edge or would close a prerequisite cycle.
     */
    @Transactional
    fun createEdge(request: CreateCompetencyEdgeRequest): CompetencyEdgeResponse {
        val (fromKey, toKey, kind) = Triple(request.fromKey, request.toKey, request.kind)

        val graph = headGraph()
        validateNewEdge(fromKey, toKey, kind, graph)

        // The row may already exist from an edge a PM removed earlier -- removal only records a
        // change row, so re-adding must reuse the row rather than violate the unique constraint.
        if (!competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(fromKey, toKey, kind)) {
            competencyEdgeRepository.save(CompetencyEdge(fromKey = fromKey, toKey = toKey, kind = kind))
        }
        competencyGraphVersionService.recordEdgeAdded(fromKey, toKey, kind)
        competencyGraphVersionService.bump()
        return CompetencyEdgeResponse(fromKey = fromKey, toKey = toKey, kind = kind)
    }

    /**
     * Removes one edge from the visible graph, leaving both of its endpoints in place.
     *
     * As with [deleteCompetency], the row stays and a change row makes it invisible.
     *
     * @throws ResponseStatusException 404 if no such edge exists.
     */
    @Transactional
    fun deleteEdge(fromKey: String, toKey: String, kind: EdgeKind): CompetencyEdgeResponse {
        val isVisible = headGraph().edges.any {
            it.fromKey == fromKey && it.toKey == toKey && it.kind == kind
        }
        if (!isVisible) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No $kind edge found from $fromKey to $toKey",
            )
        }
        competencyGraphVersionService.recordEdgeRemoved(fromKey, toKey, kind)
        competencyGraphVersionService.bump()
        return CompetencyEdgeResponse(fromKey = fromKey, toKey = toKey, kind = kind)
    }

    /**
     * Rejects a new edge that is malformed, duplicated, or would close a prerequisite cycle.
     *
     * Resolved against [graph] -- the *visible* graph -- rather than the tables, so an edge a PM
     * removed neither blocks its own re-adding nor counts towards a cycle.
     */
    private fun validateNewEdge(fromKey: String, toKey: String, kind: EdgeKind, graph: EffectiveGraph) {
        if (fromKey == toKey) {
            reject(HttpStatus.BAD_REQUEST, "A competency cannot be its own prerequisite: $fromKey")
        }
        val visibleKeys = graph.competencies.map { it.key }.toSet()
        val missingEndpoint = listOf(fromKey, toKey).firstOrNull { it !in visibleKeys }
        if (missingEndpoint != null) {
            reject(HttpStatus.NOT_FOUND, "No competency found with key: $missingEndpoint")
        }
        if (graph.edges.any { it.fromKey == fromKey && it.toKey == toKey && it.kind == kind }) {
            reject(HttpStatus.CONFLICT, "A $kind edge from $fromKey to $toKey already exists")
        }
        rejectIfCycle(fromKey, toKey, kind, graph.edges)
    }

    /**
     * Rejects an edge that would close a cycle in the prerequisite graph.
     *
     * Only [EdgeKind.PREREQUISITE] edges are checked: they are the only kind that gates and the
     * only kind [GraphTraversalService] walks, so a [EdgeKind.RELATED] loop is harmless. A cycle
     * would break both the topological ordering the path depends on and the layered layout the
     * frontend draws, and neither fails loudly -- [GraphTraversalService.topologicalOrder]
     * silently appends whatever it could not resolve.
     */
    private fun rejectIfCycle(fromKey: String, toKey: String, kind: EdgeKind, visibleEdges: List<CompetencyEdge>) {
        if (kind != EdgeKind.PREREQUISITE) return

        // Adding from -> to means "to requires from". That closes a cycle exactly when `from`
        // already depends, transitively, on `to`.
        val fromDependsOn = graphTraversalService.transitivePrerequisites(fromKey, visibleEdges)
        if (toKey in fromDependsOn) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "A prerequisite edge from $fromKey to $toKey would create a cycle: " +
                    "$fromKey already requires $toKey, directly or indirectly",
            )
        }
    }

    private fun findVisibleCompetency(key: String, graph: EffectiveGraph): Competency =
        graph.competencies.firstOrNull { it.key == key }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No competency found with key: $key")

    companion object {
        private const val MIN_TARGET_LEVEL = 1
        private const val MAX_TARGET_LEVEL = 4
    }
}

/** Pure mappers, kept top-level so the service stays under detekt's per-class function budget. */
private fun Competency.toAuthoringResponse(): CompetencyResponse =
    CompetencyResponse(
        key = key,
        label = label,
        description = description,
        kind = kind,
        targetLevel = targetLevel,
        invariant = invariant,
        repoRef = repoRef,
    )

private fun CompetencyEdge.toAuthoringResponse(): CompetencyEdgeResponse =
    CompetencyEdgeResponse(fromKey = fromKey, toKey = toKey, kind = kind)

/** Throws a [ResponseStatusException]; expression-bodied so callers stay under detekt's throw budget. */
private fun reject(status: HttpStatus, message: String): Nothing = throw ResponseStatusException(status, message)
