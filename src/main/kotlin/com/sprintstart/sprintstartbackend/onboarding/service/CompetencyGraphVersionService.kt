package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphVersion
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphChangeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Tracks the competency graph's version history and the pending changes not yet bumped into one.
 *
 * Callers that mutate the graph -- the
 * [CompetencyGraphSeeder][com.sprintstart.sprintstartbackend.onboarding.seeding.CompetencyGraphSeeder],
 * [CompetencyProposalService] approving a proposal, and
 * [CompetencyGraphAuthoringService] applying a PM's hand edit -- record each atomic change via
 * the `record*` methods, then call [bump] once per unit of work. [bump] classifies the
 * accumulated pending changes with [GraphChangeClassifier] and appends a new
 * [CompetencyGraphVersion] row -- versions are never mutated in place once recorded.
 *
 * **Every graph write must go through here.** [EffectiveGraphResolver] derives visibility purely
 * by replaying change rows, so a node or edge written straight to its table without a change row
 * is permanently invisible no matter what the table says.
 */
@Service
class CompetencyGraphVersionService(
    private val versionRepository: CompetencyGraphVersionRepository,
    private val changeRepository: CompetencyGraphChangeRepository,
    private val competencyRepository: CompetencyRepository,
    private val classifier: GraphChangeClassifier,
) {
    /** The graph's current version; `1` if it has never been bumped. */
    @Transactional(readOnly = true)
    fun currentVersion(): Int = versionRepository.findTopByOrderByVersionDesc()?.version ?: 1

    /**
     * Records that a competency node was added, pending the next [bump].
     *
     * @param key The added competency's stable key.
     */
    @Transactional
    fun recordNodeAdded(key: String) {
        changeRepository.save(
            CompetencyGraphChange(version = pendingVersion(), changeType = ChangeType.NODE_ADDED, competencyKey = key),
        )
    }

    /**
     * Records that a competency edge was added, pending the next [bump].
     *
     * @param fromKey The edge's source competency key.
     * @param toKey The edge's target competency key.
     * @param kind The edge's relationship kind.
     */
    @Transactional
    fun recordEdgeAdded(fromKey: String, toKey: String, kind: EdgeKind) {
        changeRepository.save(
            CompetencyGraphChange(
                version = pendingVersion(),
                changeType = ChangeType.EDGE_ADDED,
                fromKey = fromKey,
                toKey = toKey,
                edgeKind = kind,
            ),
        )
    }

    /**
     * Records that a competency node's content was edited, pending the next [bump].
     *
     * Caveat, deliberately not hidden: this records *that* a node changed, not what it used to
     * look like. [EffectiveGraphResolver] replays change rows to decide which keys are visible
     * and then reads content live from the `competencies` table, so an edit to a label,
     * description, kind or target level is visible to every hire immediately -- the STRUCTURAL
     * hold-back cannot defer it. Recording it still matters: it bumps the version (so clients
     * see the graph moved) and it classifies correctly for anything batched alongside it.
     * Making an edit genuinely deferrable needs per-version node content, which this table does
     * not carry.
     *
     * @param key The edited competency's stable key.
     */
    @Transactional
    fun recordNodeModified(key: String) {
        changeRepository.save(
            CompetencyGraphChange(
                version = pendingVersion(),
                changeType = ChangeType.NODE_MODIFIED,
                competencyKey = key,
            ),
        )
    }

    /**
     * Records that a competency node was removed from the graph, pending the next [bump].
     *
     * Removal is recorded, never deleted: the `competencies` row stays and the node simply stops
     * being replayed into the visible set. Nothing a hire earned is touched -- their
     * `UserCompetencyState` rows are independent of graph visibility.
     *
     * @param key The removed competency's stable key.
     */
    @Transactional
    fun recordNodeRemoved(key: String) {
        changeRepository.save(
            CompetencyGraphChange(
                version = pendingVersion(),
                changeType = ChangeType.NODE_REMOVED,
                competencyKey = key,
            ),
        )
    }

    /**
     * Records that a competency edge was removed, pending the next [bump].
     *
     * @param fromKey The edge's source competency key.
     * @param toKey The edge's target competency key.
     * @param kind The edge's relationship kind.
     */
    @Transactional
    fun recordEdgeRemoved(fromKey: String, toKey: String, kind: EdgeKind) {
        changeRepository.save(
            CompetencyGraphChange(
                version = pendingVersion(),
                changeType = ChangeType.EDGE_REMOVED,
                fromKey = fromKey,
                toKey = toKey,
                edgeKind = kind,
            ),
        )
    }

    /**
     * Classifies the changes recorded since the last bump and appends a new version row.
     *
     * A no-op (returns the current version unchanged) when nothing has been recorded via
     * [recordNodeAdded]/[recordEdgeAdded] since the last bump.
     *
     * @return The new current version.
     */
    @Transactional
    fun bump(): Int {
        val latest = versionRepository.findTopByOrderByVersionDesc()
        val newVersion = (latest?.version ?: 0) + 1
        val pendingChanges = changeRepository.findAllByVersion(newVersion)
        if (pendingChanges.isEmpty()) return latest?.version ?: 1

        val competenciesByKey = competencyRepository.findAll().associateBy { it.key }
        val classification = classifier.classify(pendingChanges, competenciesByKey)
        versionRepository.save(CompetencyGraphVersion(version = newVersion, classification = classification))
        return newVersion
    }

    private fun pendingVersion(): Int = (versionRepository.findTopByOrderByVersionDesc()?.version ?: 0) + 1
}
