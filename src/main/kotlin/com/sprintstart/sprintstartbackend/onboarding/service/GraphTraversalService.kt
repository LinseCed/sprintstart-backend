package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import org.springframework.stereotype.Service

/**
 * Pure graph algorithms over the competency graph's [EdgeKind.PREREQUISITE] edges.
 *
 * No repository or database access -- callers supply the edges to operate on. This is the
 * traversal layer [Competency][com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency]'s
 * and [CompetencyEdge]'s doc comments deferred to "Phase 2".
 */
@Service
class GraphTraversalService {
    /**
     * Orders [keys] so every prerequisite precedes what it unlocks (Kahn's algorithm).
     *
     * Only [EdgeKind.PREREQUISITE] edges between two members of [keys] participate. If the
     * induced subgraph contains a cycle (shouldn't happen for well-formed graph data), whatever
     * remains unresolved is appended in [keys]'s iteration order rather than throwing.
     */
    fun topologicalOrder(keys: Set<String>, edges: List<CompetencyEdge>): List<String> {
        val prerequisiteEdges = edges.filter {
            it.kind == EdgeKind.PREREQUISITE && it.fromKey in keys && it.toKey in keys
        }
        val successorsByKey = prerequisiteEdges.groupBy({ it.fromKey }, { it.toKey })
        val remainingInDegree = keys.associateWith { 0 }.toMutableMap()
        for (edge in prerequisiteEdges) {
            remainingInDegree[edge.toKey] = remainingInDegree.getValue(edge.toKey) + 1
        }

        val queue = ArrayDeque(keys.filter { remainingInDegree.getValue(it) == 0 })
        val ordered = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            ordered.add(key)
            for (successor in successorsByKey[key].orEmpty()) {
                val degree = remainingInDegree.getValue(successor) - 1
                remainingInDegree[successor] = degree
                if (degree == 0) queue.addLast(successor)
            }
        }

        if (ordered.size < keys.size) {
            val resolved = ordered.toSet()
            ordered.addAll(keys.filter { it !in resolved })
        }

        return ordered
    }

    /**
     * Every key transitively reachable from [key] by walking [EdgeKind.PREREQUISITE] edges
     * backwards -- i.e. every competency [key] depends on, directly or indirectly. Excludes
     * [key] itself.
     */
    fun transitivePrerequisites(key: String, edges: List<CompetencyEdge>): Set<String> {
        val prerequisitesByKey = edges
            .filter { it.kind == EdgeKind.PREREQUISITE }
            .groupBy({ it.toKey }, { it.fromKey })

        val visited = mutableSetOf<String>()
        val stack = ArrayDeque(prerequisitesByKey[key].orEmpty())
        while (stack.isNotEmpty()) {
            val prerequisite = stack.removeFirst()
            if (!visited.add(prerequisite)) continue
            stack.addAll(prerequisitesByKey[prerequisite].orEmpty())
        }
        return visited
    }
}
