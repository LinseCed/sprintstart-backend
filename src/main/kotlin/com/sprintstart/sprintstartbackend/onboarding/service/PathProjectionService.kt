package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathEdge
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathNode
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Projects a hire's personalized path from the competency graph, a target set of competencies,
 * and their progress ledger.
 *
 * Pure and deterministic -- no persistence or side effects. This is the core of both
 * personalization and (later) reconciliation: rerunning it must never touch
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState]; only the path
 * itself is a disposable projection safe to recompute.
 */
@Service
class PathProjectionService(
    private val graphTraversalService: GraphTraversalService,
) {
    /**
     * @param competencies All known competency nodes.
     * @param edges All known competency edges.
     * @param targetKeys The competency keys this path should terminate in.
     * @param ledger The hire's durable progress: competency key -> assessed/verified level (0..4).
     * @param graphVersion The competency graph version being projected against; echoed onto the
     * returned [PathView] as-is (this function stays pure -- the caller looks the version up).
     * @param stepIdByCompetencyKey The onboarding step configured to teach/verify each competency
     * key, if any (see [com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification]).
     * Echoed onto each [PathNode] as-is so a client can open a node as a learn-verify module.
     * @param verificationTypeByCompetencyKey The grading type of that same
     * [com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification], keyed the same
     * way. Echoed onto each [PathNode] so a client can recognize e.g. an artifact-checked node
     * without fetching each step's verification config individually.
     * @return [targetKeys] plus their transitive prerequisites, topologically ordered, each
     * annotated with its [NodeState]; edges are restricted to pairs where both ends are returned.
     */
    fun project(
        competencies: List<Competency>,
        edges: List<CompetencyEdge>,
        targetKeys: Set<String>,
        ledger: Map<String, Int>,
        graphVersion: Int,
        stepIdByCompetencyKey: Map<String, UUID> = emptyMap(),
        verificationTypeByCompetencyKey: Map<String, VerificationType> = emptyMap(),
    ): PathView {
        val competenciesByKey = competencies.associateBy { it.key }
        val relevantKeys = linkedSetOf<String>()
        for (key in targetKeys) {
            if (key in competenciesByKey) relevantKeys += key
            relevantKeys += graphTraversalService
                .transitivePrerequisites(key, edges)
                .filter { it in competenciesByKey }
        }

        val orderedKeys = graphTraversalService.topologicalOrder(relevantKeys, edges)

        val directPrerequisitesByKey = edges
            .filter { it.kind == EdgeKind.PREREQUISITE && it.fromKey in relevantKeys && it.toKey in relevantKeys }
            .groupBy({ it.toKey }, { it.fromKey })

        val states = mutableMapOf<String, NodeState>()
        for (key in orderedKeys) {
            val level = ledger[key]
            states[key] = when {
                level != null && level > 0 -> NodeState.MASTERED
                directPrerequisitesByKey[key].orEmpty().all { states[it] == NodeState.MASTERED } -> NodeState.AVAILABLE
                else -> NodeState.LOCKED
            }
        }

        val nodes = orderedKeys.map { key ->
            val competency = competenciesByKey.getValue(key)
            PathNode(
                key = competency.key,
                label = competency.label,
                kind = competency.kind,
                state = states.getValue(key),
                level = ledger[key],
                stepId = stepIdByCompetencyKey[key],
                verificationType = verificationTypeByCompetencyKey[key],
            )
        }

        val resultEdges = edges
            .filter { it.fromKey in relevantKeys && it.toKey in relevantKeys }
            .map { PathEdge(from = it.fromKey, to = it.toKey) }

        return PathView(nodes = nodes, edges = resultEdges, graphVersion = graphVersion)
    }
}
