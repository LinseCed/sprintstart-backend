package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GraphChangeClassifierTest {
    private val classifier = GraphChangeClassifier()

    private fun competency(key: String, invariant: Boolean = false) =
        Competency(key = key, label = key, kind = CompetencyKind.SKILL, invariant = invariant)

    @Test
    fun `classifies an empty batch as additive`() {
        assertThat(classifier.classify(emptyList(), emptyMap())).isEqualTo(ChangeClassification.ADDITIVE)
    }

    @Test
    fun `classifies a new node and its own new edge as additive`() {
        val changes = listOf(
            CompetencyGraphChange(version = 2, changeType = ChangeType.NODE_ADDED, competencyKey = "rust"),
            CompetencyGraphChange(
                version = 2,
                changeType = ChangeType.EDGE_ADDED,
                fromKey = "rust",
                toKey = "systems-programming",
                edgeKind = EdgeKind.PREREQUISITE,
            ),
            CompetencyGraphChange(
                version = 2,
                changeType = ChangeType.NODE_ADDED,
                competencyKey = "systems-programming",
            ),
        )
        val competenciesByKey = mapOf(
            "rust" to competency("rust"),
            "systems-programming" to competency("systems-programming"),
        )

        assertThat(classifier.classify(changes, competenciesByKey)).isEqualTo(ChangeClassification.ADDITIVE)
    }

    @Test
    fun `classifies a new prerequisite edge into a pre-existing node as structural`() {
        val changes = listOf(
            CompetencyGraphChange(
                version = 2,
                changeType = ChangeType.EDGE_ADDED,
                fromKey = "new-skill",
                toKey = "kotlin",
                edgeKind = EdgeKind.PREREQUISITE,
            ),
        )
        val competenciesByKey = mapOf("kotlin" to competency("kotlin"))

        assertThat(classifier.classify(changes, competenciesByKey)).isEqualTo(ChangeClassification.STRUCTURAL)
    }

    @Test
    fun `classifies a related edge into a pre-existing node as additive`() {
        val changes = listOf(
            CompetencyGraphChange(
                version = 2,
                changeType = ChangeType.EDGE_ADDED,
                fromKey = "new-skill",
                toKey = "kotlin",
                edgeKind = EdgeKind.RELATED,
            ),
        )
        val competenciesByKey = mapOf("kotlin" to competency("kotlin"))

        assertThat(classifier.classify(changes, competenciesByKey)).isEqualTo(ChangeClassification.ADDITIVE)
    }

    @Test
    fun `classifies a node removal as structural`() {
        val changes = listOf(
            CompetencyGraphChange(version = 2, changeType = ChangeType.NODE_REMOVED, competencyKey = "kotlin"),
        )

        val result = classifier.classify(changes, mapOf("kotlin" to competency("kotlin")))

        assertThat(result).isEqualTo(ChangeClassification.STRUCTURAL)
    }

    @Test
    fun `invariant flag wins over an otherwise additive shape`() {
        val changes = listOf(
            CompetencyGraphChange(version = 2, changeType = ChangeType.NODE_ADDED, competencyKey = "security-policy"),
        )
        val competenciesByKey = mapOf("security-policy" to competency("security-policy", invariant = true))

        assertThat(classifier.classify(changes, competenciesByKey)).isEqualTo(ChangeClassification.INVARIANT)
    }

    @Test
    fun `invariant flag wins over an otherwise structural shape`() {
        val changes = listOf(
            CompetencyGraphChange(
                version = 2,
                changeType = ChangeType.NODE_MODIFIED,
                competencyKey = "security-policy",
            ),
        )
        val competenciesByKey = mapOf("security-policy" to competency("security-policy", invariant = true))

        assertThat(classifier.classify(changes, competenciesByKey)).isEqualTo(ChangeClassification.INVARIANT)
    }

    @Test
    fun `treats an edge referencing an unknown key conservatively as structural`() {
        val changes = listOf(
            CompetencyGraphChange(
                version = 2,
                changeType = ChangeType.EDGE_ADDED,
                fromKey = "new-skill",
                toKey = "unknown-key",
                edgeKind = EdgeKind.PREREQUISITE,
            ),
        )

        assertThat(classifier.classify(changes, emptyMap())).isEqualTo(ChangeClassification.STRUCTURAL)
    }
}
