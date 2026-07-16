package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import org.springframework.stereotype.Service

/**
 * Classifies a batch of pending [CompetencyGraphChange] rows -- the atomic mutations recorded for
 * one not-yet-bumped graph version -- as [ChangeClassification.INVARIANT], [ChangeClassification.ADDITIVE],
 * or [ChangeClassification.STRUCTURAL].
 *
 * Pure and deterministic -- no persistence or side effects. [competenciesByKey] must include
 * every competency touched by [changes] (both pre-existing and newly introduced in this same
 * batch); a change referencing a key missing from that map is treated conservatively as
 * structural rather than assumed safe.
 *
 * Precedence: any change touching a [Competency.invariant]-flagged competency makes the whole
 * batch [ChangeClassification.INVARIANT], regardless of shape. Otherwise, any removal or
 * modification, or any [EdgeKind.PREREQUISITE] edge added into a node that already existed before
 * this batch, makes the batch [ChangeClassification.STRUCTURAL] -- it could narrow or re-lock
 * something a hire already depends on. A batch of only new nodes and edges among them is
 * [ChangeClassification.ADDITIVE].
 */
@Service
class GraphChangeClassifier {
    fun classify(
        changes: List<CompetencyGraphChange>,
        competenciesByKey: Map<String, Competency>,
    ): ChangeClassification {
        if (changes.isEmpty()) return ChangeClassification.ADDITIVE

        if (changes.any { touchesInvariantCompetency(it, competenciesByKey) }) {
            return ChangeClassification.INVARIANT
        }

        val newlyIntroducedKeys = changes
            .filter { it.changeType == ChangeType.NODE_ADDED }
            .mapNotNull { it.competencyKey }
            .toSet()

        val isStructural = changes.any { isStructuralChange(it, newlyIntroducedKeys) }

        return if (isStructural) ChangeClassification.STRUCTURAL else ChangeClassification.ADDITIVE
    }

    private fun touchesInvariantCompetency(
        change: CompetencyGraphChange,
        competenciesByKey: Map<String, Competency>,
    ): Boolean {
        val keys = listOfNotNull(change.competencyKey, change.fromKey, change.toKey)
        return keys.any { competenciesByKey[it]?.invariant == true }
    }

    private fun isStructuralChange(change: CompetencyGraphChange, newlyIntroducedKeys: Set<String>): Boolean =
        when (change.changeType) {
            ChangeType.NODE_REMOVED, ChangeType.NODE_MODIFIED,
            ChangeType.EDGE_REMOVED, ChangeType.EDGE_MODIFIED,
            -> true

            ChangeType.EDGE_ADDED ->
                change.edgeKind == EdgeKind.PREREQUISITE && change.toKey !in newlyIntroducedKeys

            ChangeType.NODE_ADDED -> false
        }
}
