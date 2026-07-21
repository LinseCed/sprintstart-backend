package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathEdge
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

class PathProjectionServiceTest {
    private val service = PathProjectionService(GraphTraversalService())

    // Most cases here are about traversal rather than the bar, so they pin targetLevel = 1 to keep
    // "has a ledger entry" and "is mastered" the same thing. The entity's real default is asserted
    // separately below, since it is what a competency gets until a PM authors a bar.
    private fun node(key: String, kind: CompetencyKind = CompetencyKind.SKILL, targetLevel: Int = 1) =
        Competency(key = key, label = key, kind = kind, targetLevel = targetLevel)

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
                graphVersion = 1,
            )

            assertThat(stateOf(result, "git")).isEqualTo(NodeState.AVAILABLE)
        }

        @Test
        fun `a node with an unmet prerequisite is available, not locked -- an edge ranks, it never bars`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(prerequisite("git", "kotlin")),
                targetKeys = setOf("kotlin"),
                ledger = emptyMap(),
                graphVersion = 1,
            )

            // The prerequisite still travels on the view to explain ordering, but it no longer
            // gates: a hire is never told "not yet", only "this one fits better".
            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.AVAILABLE)
            assertThat(result.edges).containsExactly(PathEdge("git", "kotlin"))
        }

        @Test
        fun `no node is ever unreachable because of an edge`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin"), node("domain-model")),
                edges = listOf(prerequisite("git", "kotlin"), prerequisite("kotlin", "domain-model")),
                targetKeys = setOf("domain-model"),
                ledger = emptyMap(),
                graphVersion = 1,
            )

            assertThat(result.nodes.map { it.state }).allMatch { it == NodeState.AVAILABLE }
        }

        @Test
        fun `a node with all prerequisites mastered is available`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(prerequisite("git", "kotlin")),
                targetKeys = setOf("kotlin"),
                ledger = mapOf("git" to 3),
                graphVersion = 1,
            )

            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.AVAILABLE)
        }

        @Test
        fun `a ledger entry at or above the node's target level means mastered, regardless of prerequisites`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(prerequisite("git", "kotlin")),
                targetKeys = setOf("kotlin"),
                ledger = mapOf("kotlin" to 1),
                graphVersion = 1,
            )

            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.MASTERED)
        }

        @Test
        fun `a ledger entry below the node's target level is not mastery`() {
            val result = service.project(
                competencies = listOf(node("kotlin", targetLevel = 3)),
                edges = emptyList(),
                targetKeys = setOf("kotlin"),
                ledger = mapOf("kotlin" to 1),
                graphVersion = 1,
            )

            // Being placed at beginner on a node that demands advanced is progress, not completion.
            // Treating any non-zero level as mastery is what marked a self-declared beginner done.
            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.AVAILABLE)
        }

        @Test
        fun `a competency with no authored bar is not mastered by a beginner placement`() {
            // The default bar is what nearly every competency runs on today -- nothing populates
            // target_level yet -- so it carries the whole fix. A default of 1 would re-open the bug.
            val default = Competency(key = "kotlin", label = "kotlin", kind = CompetencyKind.SKILL)

            fun stateAtLevel(level: Int) = stateOf(
                service.project(
                    competencies = listOf(default),
                    edges = emptyList(),
                    targetKeys = setOf("kotlin"),
                    ledger = mapOf("kotlin" to level),
                    graphVersion = 1,
                ),
                "kotlin",
            )

            assertThat(stateAtLevel(1)).isEqualTo(NodeState.AVAILABLE)
            assertThat(stateAtLevel(2)).isEqualTo(NodeState.MASTERED)
        }

        @Test
        fun `a level-0 ledger entry is not mastery`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(prerequisite("git", "kotlin")),
                targetKeys = setOf("kotlin"),
                ledger = mapOf("git" to 0),
                graphVersion = 1,
            )

            // 0 means "we asked and saw no competence" -- it is not mastery. The dependent is
            // available regardless: an unmet prerequisite ranks the work, it does not lock it.
            assertThat(stateOf(result, "git")).isEqualTo(NodeState.AVAILABLE)
            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.AVAILABLE)
        }

        @Test
        fun `a newly added prerequisite never re-locks a node the ledger already holds`() {
            // Simulates a structural graph change: "advanced-kotlin" was mastered before "new-tool"
            // (and its prerequisite edge into "advanced-kotlin") existed in the graph at all.
            val result = service.project(
                competencies = listOf(node("advanced-kotlin"), node("new-tool")),
                edges = listOf(prerequisite("new-tool", "advanced-kotlin")),
                targetKeys = setOf("advanced-kotlin"),
                ledger = mapOf("advanced-kotlin" to 3),
                graphVersion = 2,
            )

            assertThat(stateOf(result, "advanced-kotlin")).isEqualTo(NodeState.MASTERED)
        }

        @Test
        fun `a related edge never gates availability`() {
            val result = service.project(
                competencies = listOf(node("git"), node("kotlin")),
                edges = listOf(related("git", "kotlin")),
                targetKeys = setOf("git", "kotlin"),
                ledger = emptyMap(),
                graphVersion = 1,
            )

            assertThat(stateOf(result, "kotlin")).isEqualTo(NodeState.AVAILABLE)
        }
    }

    @Nested
    inner class Legibility {
        @Test
        fun `a fuller ledger yields a superset of mastered nodes and a subset of still-open ones`() {
            val competencies = listOf(node("git"), node("kotlin"), node("spring-boot"), node("domain-model"))
            val edges = listOf(
                prerequisite("kotlin", "domain-model"),
                prerequisite("spring-boot", "domain-model"),
            )
            val targetKeys = setOf("git", "kotlin", "spring-boot", "domain-model")

            val seniorLedger = mapOf("git" to 3, "kotlin" to 3, "spring-boot" to 2)
            val juniorLedger = mapOf("git" to 1)

            val seniorPath = service.project(competencies, edges, targetKeys, seniorLedger, graphVersion = 1)
            val juniorPath = service.project(competencies, edges, targetKeys, juniorLedger, graphVersion = 1)

            fun keysInState(path: PathView, state: NodeState) =
                path.nodes
                    .filter { it.state == state }
                    .map { it.key }
                    .toSet()

            // Standing, not permission: a node is either shown (MASTERED) or still open (AVAILABLE).
            // A fuller ledger has strictly more mastered nodes and strictly fewer still-open ones.
            val seniorMastered = keysInState(seniorPath, NodeState.MASTERED)
            val juniorMastered = keysInState(juniorPath, NodeState.MASTERED)
            assertThat(seniorMastered).containsAll(juniorMastered)
            assertThat(seniorMastered.size).isGreaterThan(juniorMastered.size)

            val seniorOpen = keysInState(seniorPath, NodeState.AVAILABLE)
            val juniorOpen = keysInState(juniorPath, NodeState.AVAILABLE)
            assertThat(juniorOpen).containsAll(seniorOpen)
            assertThat(seniorOpen.size).isLessThan(juniorOpen.size)
        }
    }

    @Nested
    inner class GraphVersion {
        @Test
        fun `echoes the given graph version onto the result`() {
            val result = service.project(
                competencies = listOf(node("git")),
                edges = emptyList(),
                targetKeys = setOf("git"),
                ledger = emptyMap(),
                graphVersion = 42,
            )

            assertThat(result.graphVersion).isEqualTo(42)
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
                graphVersion = 1,
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
                graphVersion = 1,
            )

            assertThat(result.edges).containsExactly(
                PathEdge("kotlin", "domain-model"),
            )
        }
    }

    @Nested
    inner class StepIdAnnotation {
        @Test
        fun `annotates a node with its configured step id when one is given`() {
            val stepId = UUID.randomUUID()

            val result = service.project(
                competencies = listOf(node("kotlin"), node("git")),
                edges = emptyList(),
                targetKeys = setOf("kotlin", "git"),
                ledger = emptyMap(),
                graphVersion = 1,
                moduleIdByCompetencyKey = mapOf("kotlin" to stepId),
            )

            assertThat(result.nodes.first { it.key == "kotlin" }.moduleId).isEqualTo(stepId)
            assertThat(result.nodes.first { it.key == "git" }.moduleId).isNull()
        }

        @Test
        fun `defaults every node's step id to null when no map is given`() {
            val result = service.project(
                competencies = listOf(node("kotlin")),
                edges = emptyList(),
                targetKeys = setOf("kotlin"),
                ledger = emptyMap(),
                graphVersion = 1,
            )

            assertThat(result.nodes.single().moduleId).isNull()
        }
    }

    @Nested
    inner class VerificationTypeAnnotation {
        @Test
        fun `annotates a node with its configured verification type when one is given`() {
            val result = service.project(
                competencies = listOf(node("kotlin"), node("git")),
                edges = emptyList(),
                targetKeys = setOf("kotlin", "git"),
                ledger = emptyMap(),
                graphVersion = 1,
                verificationTypeByCompetencyKey = mapOf("kotlin" to VerificationType.ARTIFACT),
            )

            assertThat(result.nodes.first { it.key == "kotlin" }.verificationType)
                .isEqualTo(VerificationType.ARTIFACT)
            assertThat(result.nodes.first { it.key == "git" }.verificationType).isNull()
        }

        @Test
        fun `defaults every node's verification type to null when no map is given`() {
            val result = service.project(
                competencies = listOf(node("kotlin")),
                edges = emptyList(),
                targetKeys = setOf("kotlin"),
                ledger = emptyMap(),
                graphVersion = 1,
            )

            assertThat(result.nodes.single().verificationType).isNull()
        }
    }
}
