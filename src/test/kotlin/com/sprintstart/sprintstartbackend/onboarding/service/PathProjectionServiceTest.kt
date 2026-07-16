package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathEdge
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PathProjectionServiceTest {
    private val service = PathProjectionService(GraphTraversalService())

    private fun node(key: String, kind: CompetencyKind = CompetencyKind.SKILL) =
        Competency(key = key, label = key, kind = kind)

    private fun prerequisite(from: String, to: String) =
        CompetencyEdge(fromKey = from, toKey = to, kind = EdgeKind.PREREQUISITE)

    private fun related(from: String, to: String) =
        CompetencyEdge(fromKey = from, toKey = to, kind = EdgeKind.RELATED)

    private fun stateOf(result: PathView, key: String) =
        result.nodes.first { it.key == key }.state

    @Nested
    inner class StateDerivation {
        @Test
        fun `a node with no prerequisites and no ledger entry is available`() {
            val result = service.project(
                competencies = listOf(node("git")),
                edges = emptyList(),
                targetKeys = setOf("git"),
                ledger = emptyMap(),
            )

            assertThat(stateOf(result, "git")).isEqualTo(NodeState.AVAILABLE)
        }

        @Test
        fun `a node with an unmet prerequisite is locked`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(prerequisite("git", "kotlin")),
                targetKeys = setOf("kotlin"),
                ledger = emptyMap(),
            )

            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.LOCKED)
        }

        @Test
        fun `a node with all prerequisites mastered is available`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(prerequisite("git", "kotlin")),
                targetKeys = setOf("kotlin"),
                ledger = mapOf("git" to 3),
            )

            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.AVAILABLE)
        }

        @Test
        fun `a ledger entry above zero always means mastered, regardless of prerequisites`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(prerequisite("git", "kotlin")),
                targetKeys = setOf("kotlin"),
                ledger = mapOf("kotlin" to 1),
            )

            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.MASTERED)
        }

        @Test
        fun `a related edge never gates availability`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(related("git", "kotlin")),
                targetKeys = setOf("git", "kotlin"),
                ledger = emptyMap(),
            )

            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.AVAILABLE)
        }
    }

    @Nested
    inner class Legibility {
        @Test
        fun `a fuller ledger yields a superset of mastered nodes and a subset of locked ones`() {
            val competencies = listOf(node("git"), node("kotlin"), node("spring-boot"), node("domain-model"))
            val edges = listOf(
                prerequisite("kotlin", "domain-model"),
                prerequisite("spring-boot", "domain-model"),
            )
            val targetKeys = setOf("git", "kotlin", "spring-boot", "domain-model")

            val seniorLedger = mapOf("git" to 3, "kotlin" to 3, "spring-boot" to 2)
            val juniorLedger = mapOf("git" to 1)

            val seniorPath = service.project(competencies, edges, targetKeys, seniorLedger)
            val juniorPath = service.project(competencies, edges, targetKeys, juniorLedger)

            fun keysInState(path: PathView, state: NodeState) =
                path.nodes
                    .filter { it.state == state }
                    .map { it.key }
                    .toSet()

            val seniorMastered = keysInState(seniorPath, NodeState.MASTERED)
            val juniorMastered = keysInState(juniorPath, NodeState.MASTERED)
            assertThat(seniorMastered).containsAll(juniorMastered)
            assertThat(seniorMastered.size).isGreaterThan(juniorMastered.size)

            val seniorLocked = keysInState(seniorPath, NodeState.LOCKED)
            val juniorLocked = keysInState(juniorPath, NodeState.LOCKED)
            assertThat(juniorLocked).containsAll(seniorLocked)
            assertThat(seniorLocked.size).isLessThan(juniorLocked.size)
        }
    }

    @Nested
    inner class NodeAndEdgeSet {
        @Test
        fun `includes a target's transitive prerequisites even when they are not themselves targets`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin"), node("domain-model")),
                edges = listOf(prerequisite("git", "kotlin"), prerequisite("kotlin", "domain-model")),
                targetKeys = setOf("domain-model"),
                ledger = emptyMap(),
            )

            assertThat(result.nodes.map { it.key }).containsExactlyInAnyOrder("git", "kotlin", "domain-model")
        }

        @Test
        fun `only returns edges where both endpoints are in the result`() {
            val result = service.project(
                competencies = listOf(node("kotlin"), node("domain-model")),
                edges = listOf(
                    prerequisite("kotlin", "domain-model"),
                    prerequisite("outside", "domain-model"),
                ),
                targetKeys = setOf("domain-model", "kotlin"),
                ledger = emptyMap(),
            )

            assertThat(result.edges).containsExactly(
                PathEdge("kotlin", "domain-model"),
            )
        }
    }
}
