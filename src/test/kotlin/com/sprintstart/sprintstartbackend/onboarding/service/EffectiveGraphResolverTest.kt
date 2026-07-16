package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EffectiveGraphResolverTest {
    private val resolver = EffectiveGraphResolver()

    private val kotlin = Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
    private val advancedKotlin =
        Competency(key = "advanced-kotlin", label = "Advanced Kotlin", kind = CompetencyKind.SKILL)
    private val allCompetencies = listOf(kotlin, advancedKotlin)

    private val changes = listOf(
        // version 1 (additive baseline): kotlin introduced
        CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = "kotlin"),
        // version 2 (structural): advanced-kotlin introduced with a prerequisite edge into kotlin,
        // which already existed before this version
        CompetencyGraphChange(version = 2, changeType = ChangeType.NODE_ADDED, competencyKey = "advanced-kotlin"),
        CompetencyGraphChange(
            version = 2,
            changeType = ChangeType.EDGE_ADDED,
            fromKey = "advanced-kotlin",
            toKey = "kotlin",
            edgeKind = EdgeKind.PREREQUISITE,
        ),
    )

    private val edges =
        listOf(CompetencyEdge(fromKey = "advanced-kotlin", toKey = "kotlin", kind = EdgeKind.PREREQUISITE))

    private val versionHistory = listOf(
        CompetencyGraphVersion(version = 1, classification = ChangeClassification.ADDITIVE),
        CompetencyGraphVersion(version = 2, classification = ChangeClassification.STRUCTURAL),
    )

    @Test
    fun `a senior hire pinned at the current version sees the full graph`() {
        val result = resolver.resolve(
            pinnedVersion = 2,
            currentVersion = 2,
            versionHistory = versionHistory,
            changes = changes,
            allCompetencies = allCompetencies,
            allEdges = edges,
        )

        assertThat(result.competencies.map { it.key }).containsExactlyInAnyOrder("kotlin", "advanced-kotlin")
        assertThat(result.edges).hasSize(1)
    }

    @Test
    fun `a junior hire pinned behind a structural version does not see it yet`() {
        val result = resolver.resolve(
            pinnedVersion = 1,
            currentVersion = 2,
            versionHistory = versionHistory,
            changes = changes,
            allCompetencies = allCompetencies,
            allEdges = edges,
        )

        assertThat(result.competencies.map { it.key }).containsExactly("kotlin")
        assertThat(result.edges).isEmpty()
    }

    @Test
    fun `junior hire's visible graph is a subset of a senior hire's`() {
        val junior = resolver.resolve(1, 2, versionHistory, changes, allCompetencies, edges)
        val senior = resolver.resolve(2, 2, versionHistory, changes, allCompetencies, edges)

        assertThat(senior.competencies.map { it.key }).containsAll(junior.competencies.map { it.key })
    }

    @Test
    fun `additive and invariant changes ahead of the pin are visible immediately`() {
        val additiveOnlyHistory = listOf(
            CompetencyGraphVersion(version = 1, classification = ChangeClassification.ADDITIVE),
            CompetencyGraphVersion(version = 2, classification = ChangeClassification.ADDITIVE),
        )

        val result = resolver.resolve(
            pinnedVersion = 1,
            currentVersion = 2,
            versionHistory = additiveOnlyHistory,
            changes = changes,
            allCompetencies = allCompetencies,
            allEdges = edges,
        )

        assertThat(result.competencies.map { it.key }).containsExactlyInAnyOrder("kotlin", "advanced-kotlin")
    }

    @Test
    fun `advancing the pin after reconciliation reveals previously held-back structural content`() {
        val beforeReconciliation = resolver.resolve(1, 2, versionHistory, changes, allCompetencies, edges)
        val afterReconciliation = resolver.resolve(2, 2, versionHistory, changes, allCompetencies, edges)

        assertThat(beforeReconciliation.competencies.map { it.key }).doesNotContain("advanced-kotlin")
        assertThat(afterReconciliation.competencies.map { it.key }).contains("advanced-kotlin")
    }

    @Test
    fun `a version with no recorded classification defaults to visible`() {
        val result = resolver.resolve(
            pinnedVersion = 0,
            currentVersion = 1,
            versionHistory = emptyList(),
            changes = changes,
            allCompetencies = allCompetencies,
            allEdges = edges,
        )

        assertThat(result.competencies.map { it.key }).containsExactly("kotlin")
    }
}
