package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphVersion
import org.springframework.stereotype.Service

/** The subset of the live graph a specific hire's path should currently be projected against. */
data class EffectiveGraph(
    val competencies: List<Competency>,
    val edges: List<CompetencyEdge>,
)

/**
 * Computes the graph content visible to a hire pinned at [UserGraphPin.pinnedVersion]
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin.pinnedVersion].
 *
 * Pure and deterministic -- no persistence or side effects. Versions at or below the pin are
 * always visible; versions above the pin are visible unless classified
 * [ChangeClassification.STRUCTURAL], which stays hidden until
 * [GraphReconciliationService][com.sprintstart.sprintstartbackend.onboarding.service.GraphReconciliationService]
 * advances the pin at the hire's next session start. A version with no recorded classification
 * (e.g. pre-existing data from before this history table existed) defaults to visible rather than
 * silently hidden.
 *
 * Change rows are replayed in version order to derive which keys/edges are currently visible;
 * [allCompetencies]/[allEdges] supply their live content (label, kind, etc.) -- this resolver
 * decides *whether* a node/edge is shown, not what a hidden node looked like historically, since
 * nothing can be modified today (only added) and Phase 5 graph-authoring will need to revisit
 * this once removal/modification is real.
 */
@Service
class EffectiveGraphResolver {
    fun resolve(
        pinnedVersion: Int,
        currentVersion: Int,
        versionHistory: List<CompetencyGraphVersion>,
        changes: List<CompetencyGraphChange>,
        allCompetencies: List<Competency>,
        allEdges: List<CompetencyEdge>,
    ): EffectiveGraph {
        val classificationByVersion = versionHistory.associateBy({ it.version }, { it.classification })
        val visibleVersions = (1..currentVersion)
            .filter { version ->
                version <= pinnedVersion || classificationByVersion[version] != ChangeClassification.STRUCTURAL
            }.toSet()

        val visibleCompetencyKeys = mutableSetOf<String>()
        val visibleEdgeIds = mutableSetOf<Triple<String, String, EdgeKind>>()

        changes
            .filter { it.version in visibleVersions }
            .sortedBy { it.version }
            .forEach { change -> applyChange(change, visibleCompetencyKeys, visibleEdgeIds) }

        return EffectiveGraph(
            competencies = allCompetencies.filter { it.key in visibleCompetencyKeys },
            edges = allEdges.filter { Triple(it.fromKey, it.toKey, it.kind) in visibleEdgeIds },
        )
    }

    private fun applyChange(
        change: CompetencyGraphChange,
        visibleCompetencyKeys: MutableSet<String>,
        visibleEdgeIds: MutableSet<Triple<String, String, EdgeKind>>,
    ) {
        when (change.changeType) {
            ChangeType.NODE_ADDED, ChangeType.NODE_MODIFIED ->
                change.competencyKey?.let { visibleCompetencyKeys += it }

            ChangeType.NODE_REMOVED ->
                change.competencyKey?.let { visibleCompetencyKeys -= it }

            ChangeType.EDGE_ADDED, ChangeType.EDGE_MODIFIED ->
                edgeId(change)?.let { visibleEdgeIds += it }

            ChangeType.EDGE_REMOVED ->
                edgeId(change)?.let { visibleEdgeIds -= it }
        }
    }

    private fun edgeId(change: CompetencyGraphChange): Triple<String, String, EdgeKind>? {
        val fromKey = change.fromKey ?: return null
        val toKey = change.toKey ?: return null
        val edgeKind = change.edgeKind ?: return null
        return Triple(fromKey, toKey, edgeKind)
    }
}
