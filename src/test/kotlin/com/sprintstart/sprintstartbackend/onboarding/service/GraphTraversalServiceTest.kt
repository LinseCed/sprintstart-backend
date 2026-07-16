package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GraphTraversalServiceTest {
    private val service = GraphTraversalService()

    private fun prerequisite(from: String, to: String) =
        CompetencyEdge(fromKey = from, toKey = to, kind = EdgeKind.PREREQUISITE)

    private fun related(from: String, to: String) =
        CompetencyEdge(fromKey = from, toKey = to, kind = EdgeKind.RELATED)

    @Nested
    inner class TopologicalOrder {
        @Test
        fun `orders a prerequisite before what it unlocks`() {
            val edges = listOf(prerequisite("kotlin", "domain-model"))

            val order = service.topologicalOrder(setOf("domain-model", "kotlin"), edges)

            assertThat(order.indexOf("kotlin")).isLessThan(order.indexOf("domain-model"))
        }

        @Test
        fun `orders a chain of prerequisites transitively`() {
            val edges = listOf(
                prerequisite("git", "kotlin"),
                prerequisite("kotlin", "domain-model"),
                prerequisite("domain-model", "jpa"),
            )

            val order = service.topologicalOrder(setOf("jpa", "domain-model", "kotlin", "git"), edges)

            assertThat(order).containsExactly("git", "kotlin", "domain-model", "jpa")
        }

        @Test
        fun `ignores related edges when ordering`() {
            val edges = listOf(related("b", "a"))

            val order = service.topologicalOrder(setOf("a", "b"), edges)

            assertThat(order).containsExactlyInAnyOrder("a", "b")
        }

        @Test
        fun `ignores edges pointing outside the requested key set`() {
            val edges = listOf(prerequisite("outside", "a"))

            val order = service.topologicalOrder(setOf("a"), edges)

            assertThat(order).containsExactly("a")
        }

        @Test
        fun `does not throw on a cycle, appending unresolved keys instead`() {
            val edges = listOf(prerequisite("a", "b"), prerequisite("b", "a"))

            val order = service.topologicalOrder(setOf("a", "b"), edges)

            assertThat(order).containsExactlyInAnyOrder("a", "b")
        }
    }

    @Nested
    inner class TransitivePrerequisites {
        @Test
        fun `returns direct and indirect ancestors, not the key itself`() {
            val edges = listOf(
                prerequisite("git", "kotlin"),
                prerequisite("kotlin", "domain-model"),
            )

            val result = service.transitivePrerequisites("domain-model", edges)

            assertThat(result).containsExactlyInAnyOrder("git", "kotlin")
            assertThat(result).doesNotContain("domain-model")
        }

        @Test
        fun `returns an empty set for a node with no prerequisites`() {
            val result = service.transitivePrerequisites("git", listOf(prerequisite("git", "kotlin")))

            assertThat(result).isEmpty()
        }

        @Test
        fun `ignores related edges`() {
            val result = service.transitivePrerequisites("a", listOf(related("b", "a")))

            assertThat(result).isEmpty()
        }
    }
}
