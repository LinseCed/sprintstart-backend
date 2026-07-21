package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.CreateCompetencyEdgeRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.CreateCompetencyRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.UpdateCompetencyRequest
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphChangeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for a PM authoring the live competency graph.
 *
 * The through-line of most of these: **a write that skips the change log is a ghost.** Visibility
 * is replayed from [CompetencyGraphChange] rows, so every mutation is asserted to record one, and
 * every read is asserted to resolve against the replayed graph rather than the raw tables.
 */
class CompetencyGraphAuthoringServiceTest {
    private val competencyRepository: CompetencyRepository = mockk(relaxed = true)
    private val competencyEdgeRepository: CompetencyEdgeRepository = mockk(relaxed = true)
    private val competencyGraphChangeRepository: CompetencyGraphChangeRepository = mockk()
    private val competencyGraphVersionRepository: CompetencyGraphVersionRepository = mockk()
    private val competencyGraphVersionService: CompetencyGraphVersionService = mockk(relaxed = true)
    private val service = CompetencyGraphAuthoringService(
        competencyRepository,
        competencyEdgeRepository,
        competencyGraphChangeRepository,
        competencyGraphVersionRepository,
        competencyGraphVersionService,
        EffectiveGraphResolver(),
        GraphTraversalService(),
    )

    /**
     * Stages a live graph: [competencies] and [edges] exist as rows *and* have the NODE_ADDED /
     * EDGE_ADDED change rows that make them visible. [extraChanges] appends removals or other
     * history on top.
     */
    private fun stageGraph(
        competencies: List<Competency> = emptyList(),
        edges: List<CompetencyEdge> = emptyList(),
        extraChanges: List<CompetencyGraphChange> = emptyList(),
    ) {
        val addChanges = competencies.map {
            CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = it.key)
        } + edges.map {
            CompetencyGraphChange(
                version = 1,
                changeType = ChangeType.EDGE_ADDED,
                fromKey = it.fromKey,
                toKey = it.toKey,
                edgeKind = it.kind,
            )
        }
        every { competencyRepository.findAll() } returns competencies
        every { competencyEdgeRepository.findAll() } returns edges
        // Relaxed mocks answer a nullable reference return with a child mock, not null, so a
        // create against a fresh key would "find" a ghost row. Default to no existing row; the
        // soft-removed-reuse test overrides this for its specific key.
        every { competencyRepository.findByKey(any()) } returns null
        // A relaxed mock returns a bare Object from the generic save(S): S, which the checkcast
        // Kotlin inserts at the call site rejects -- echo the argument back instead.
        every { competencyRepository.save(any()) } answers { firstArg() }
        every { competencyEdgeRepository.save(any()) } answers { firstArg() }
        every { competencyGraphChangeRepository.findAll() } returns addChanges + extraChanges
        every { competencyGraphVersionRepository.findAllByVersionGreaterThanOrderByVersionAsc(0) } returns emptyList()
        every { competencyGraphVersionService.currentVersion() } returns 10
        every { competencyGraphVersionService.bump() } returns 11
    }

    private fun competency(
        key: String,
        label: String = "Label for $key",
        kind: CompetencyKind = CompetencyKind.SKILL,
        targetLevel: Int = Competency.DEFAULT_TARGET_LEVEL,
    ) = Competency(key = key, label = label, kind = kind, targetLevel = targetLevel)

    private fun edge(from: String, to: String, kind: EdgeKind = EdgeKind.PREREQUISITE) =
        CompetencyEdge(fromKey = from, toKey = to, kind = kind)

    @Nested
    inner class GetCompetency {
        @Test
        fun `returns the fields the projected path omits`() {
            val kotlin = competency("kotlin", label = "Kotlin", targetLevel = 3)
            kotlin.description = "What we use it for here"
            stageGraph(competencies = listOf(kotlin))

            val response = service.getCompetency("kotlin")

            assertEquals("What we use it for here", response.description)
            assertEquals(3, response.targetLevel)
            assertEquals("kotlin", response.key)
        }

        @Test
        fun `404s for a competency that was removed from the graph`() {
            stageGraph(
                competencies = listOf(competency("kotlin")),
                extraChanges = listOf(
                    CompetencyGraphChange(version = 2, changeType = ChangeType.NODE_REMOVED, competencyKey = "kotlin"),
                ),
            )

            val exception = assertThrows<ResponseStatusException> { service.getCompetency("kotlin") }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
        }
    }

    @Nested
    inner class GetGraph {
        @Test
        fun `returns every visible competency and edge with the head version`() {
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("jpa")),
                edges = listOf(edge("kotlin", "jpa")),
            )

            val response = service.getGraph()

            assertEquals(listOf("kotlin", "jpa"), response.competencies.map { it.key })
            assertEquals(listOf("kotlin" to "jpa"), response.edges.map { it.fromKey to it.toKey })
            assertEquals(10, response.graphVersion)
        }

        @Test
        fun `omits what was removed, so a PM authors against the live graph`() {
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("jpa")),
                edges = listOf(edge("kotlin", "jpa")),
                extraChanges = listOf(
                    CompetencyGraphChange(version = 2, changeType = ChangeType.NODE_REMOVED, competencyKey = "jpa"),
                    CompetencyGraphChange(
                        version = 2,
                        changeType = ChangeType.EDGE_REMOVED,
                        fromKey = "kotlin",
                        toKey = "jpa",
                        edgeKind = EdgeKind.PREREQUISITE,
                    ),
                ),
            )

            val response = service.getGraph()

            // The rows still exist -- removal is recorded, not deleted -- so reading the tables
            // directly would show a node no hire can see.
            assertEquals(listOf("kotlin"), response.competencies.map { it.key })
            assertTrue(response.edges.isEmpty())
        }

        @Test
        fun `carries the authoring fields a hire's projection leaves out`() {
            val kotlin = competency("kotlin", targetLevel = 4)
            kotlin.description = "Why it matters here"
            stageGraph(competencies = listOf(kotlin))

            val response = service.getGraph()

            assertEquals("Why it matters here", response.competencies[0].description)
            assertEquals(4, response.competencies[0].targetLevel)
        }

        @Test
        fun `an empty graph is an empty response, not a failure`() {
            stageGraph()

            val response = service.getGraph()

            assertTrue(response.competencies.isEmpty())
            assertTrue(response.edges.isEmpty())
        }
    }

    @Nested
    inner class CreateCompetency {
        @Test
        fun `saves a new node and records NODE_ADDED`() {
            stageGraph()

            val response = service.createCompetency(
                CreateCompetencyRequest(key = "docker", label = "Docker", kind = CompetencyKind.SKILL),
            )

            assertEquals("docker", response.key)
            assertEquals("Docker", response.label)
            verify(exactly = 1) { competencyRepository.save(any()) }
            // A node written without a change row is a permanently invisible ghost.
            verify(exactly = 1) { competencyGraphVersionService.recordNodeAdded("docker") }
            verify(exactly = 1) { competencyGraphVersionService.bump() }
        }

        @Test
        fun `slugifies a hand-typed key into the house style`() {
            stageGraph()

            val response = service.createCompetency(
                CreateCompetencyRequest(key = "Docker Compose!", label = "Docker Compose", kind = CompetencyKind.SKILL),
            )

            assertEquals("docker-compose", response.key)
            verify(exactly = 1) { competencyGraphVersionService.recordNodeAdded("docker-compose") }
        }

        @Test
        fun `defaults the target level to the intermediate bar when omitted`() {
            stageGraph()

            val response = service.createCompetency(
                CreateCompetencyRequest(key = "docker", label = "Docker", kind = CompetencyKind.SKILL),
            )

            assertEquals(Competency.DEFAULT_TARGET_LEVEL, response.targetLevel)
        }

        @Test
        fun `rejects a key that is blank once slugified`() {
            stageGraph()

            val exception = assertThrows<ResponseStatusException> {
                service.createCompetency(
                    CreateCompetencyRequest(key = "  !! ", label = "X", kind = CompetencyKind.SKILL),
                )
            }

            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            verify(exactly = 0) { competencyGraphVersionService.bump() }
        }

        @Test
        fun `rejects a blank label`() {
            stageGraph()

            val exception = assertThrows<ResponseStatusException> {
                service.createCompetency(
                    CreateCompetencyRequest(key = "docker", label = "  ", kind = CompetencyKind.SKILL),
                )
            }

            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        }

        @Test
        fun `rejects a target level outside 1 to 4`() {
            stageGraph()

            val exception = assertThrows<ResponseStatusException> {
                service.createCompetency(
                    CreateCompetencyRequest(
                        key = "docker",
                        label = "Docker",
                        kind = CompetencyKind.SKILL,
                        targetLevel = 0,
                    ),
                )
            }

            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            verify(exactly = 0) { competencyGraphVersionService.bump() }
        }

        @Test
        fun `409s when a visible competency already has the key`() {
            stageGraph(competencies = listOf(competency("kotlin")))

            val exception = assertThrows<ResponseStatusException> {
                service.createCompetency(
                    CreateCompetencyRequest(key = "Kotlin", label = "Kotlin", kind = CompetencyKind.SKILL),
                )
            }

            assertEquals(HttpStatus.CONFLICT, exception.statusCode)
            verify(exactly = 0) { competencyRepository.save(any()) }
        }

        @Test
        fun `reuses a soft-removed row instead of inserting a duplicate`() {
            // The row survived removal, so a fresh insert would violate the unique key constraint.
            val removed = competency("kotlin")
            stageGraph(
                competencies = listOf(removed),
                extraChanges = listOf(
                    CompetencyGraphChange(version = 2, changeType = ChangeType.NODE_REMOVED, competencyKey = "kotlin"),
                ),
            )
            every { competencyRepository.findByKey("kotlin") } returns removed

            val response = service.createCompetency(
                CreateCompetencyRequest(key = "kotlin", label = "Kotlin Reloaded", kind = CompetencyKind.CONCEPT),
            )

            // Not a 409 -- the key is not visible -- and the existing row is re-authored, not duplicated.
            assertEquals("kotlin", response.key)
            assertEquals("Kotlin Reloaded", response.label)
            assertEquals(CompetencyKind.CONCEPT, response.kind)
            verify(exactly = 1) { competencyGraphVersionService.recordNodeAdded("kotlin") }
        }
    }

    @Nested
    inner class UpdateCompetency {
        @Test
        fun `applies only the supplied fields and leaves the rest alone`() {
            val kotlin = competency("kotlin", label = "Kotlin", targetLevel = 2)
            kotlin.description = "The original description"
            stageGraph(competencies = listOf(kotlin))

            val response = service.updateCompetency(
                "kotlin",
                UpdateCompetencyRequest(label = "Kotlin Basics", targetLevel = 3),
            )

            assertEquals("Kotlin Basics", response.label)
            assertEquals(3, response.targetLevel)
            // Untouched by an omitted field, which is the whole point of the partial update.
            assertEquals("The original description", response.description)
            assertEquals(CompetencyKind.SKILL, response.kind)
        }

        @Test
        fun `records a NODE_MODIFIED change and bumps the version`() {
            stageGraph(competencies = listOf(competency("kotlin")))

            service.updateCompetency("kotlin", UpdateCompetencyRequest(label = "Kotlin Basics"))

            // Without this the edit would be invisible to the reconciliation path entirely.
            verify(exactly = 1) { competencyGraphVersionService.recordNodeModified("kotlin") }
            verify(exactly = 1) { competencyGraphVersionService.bump() }
        }

        @Test
        fun `a blank description clears it rather than storing whitespace`() {
            val kotlin = competency("kotlin")
            kotlin.description = "Something"
            stageGraph(competencies = listOf(kotlin))

            val response = service.updateCompetency("kotlin", UpdateCompetencyRequest(description = "   "))

            assertNull(response.description)
        }

        @Test
        fun `rejects a target level outside 1 to 4`() {
            stageGraph(competencies = listOf(competency("kotlin")))

            val exception = assertThrows<ResponseStatusException> {
                service.updateCompetency("kotlin", UpdateCompetencyRequest(targetLevel = 5))
            }

            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            verify(exactly = 0) { competencyGraphVersionService.bump() }
        }

        @Test
        fun `rejects a blank label`() {
            stageGraph(competencies = listOf(competency("kotlin")))

            val exception = assertThrows<ResponseStatusException> {
                service.updateCompetency("kotlin", UpdateCompetencyRequest(label = "  "))
            }

            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        }

        @Test
        fun `404s for a competency that was removed from the graph`() {
            // The row still exists -- removal is recorded, not deleted -- so this only 404s if
            // the service resolves against the replayed graph rather than the table.
            stageGraph(
                competencies = listOf(competency("kotlin")),
                extraChanges = listOf(
                    CompetencyGraphChange(version = 2, changeType = ChangeType.NODE_REMOVED, competencyKey = "kotlin"),
                ),
            )

            val exception = assertThrows<ResponseStatusException> {
                service.updateCompetency("kotlin", UpdateCompetencyRequest(label = "Kotlin"))
            }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
        }
    }

    @Nested
    inner class DeleteCompetency {
        @Test
        fun `records the node and every edge touching it as removed`() {
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("spring"), competency("testing")),
                edges = listOf(edge("kotlin", "spring"), edge("testing", "kotlin"), edge("testing", "spring")),
            )

            val response = service.deleteCompetency("kotlin")

            assertEquals(2, response.edgesRemoved)
            verify(exactly = 1) { competencyGraphVersionService.recordNodeRemoved("kotlin") }
            verify(exactly = 1) {
                competencyGraphVersionService.recordEdgeRemoved("kotlin", "spring", EdgeKind.PREREQUISITE)
            }
            verify(exactly = 1) {
                competencyGraphVersionService.recordEdgeRemoved("testing", "kotlin", EdgeKind.PREREQUISITE)
            }
            // The edge between the two survivors is none of this node's business.
            verify(exactly = 0) {
                competencyGraphVersionService.recordEdgeRemoved("testing", "spring", EdgeKind.PREREQUISITE)
            }
        }

        @Test
        fun `deletes no rows, so nothing a hire earned can be lost`() {
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("spring")),
                edges = listOf(edge("kotlin", "spring")),
            )

            service.deleteCompetency("kotlin")

            // The ledger keys off the competency key; deleting the row is what would strand it.
            verify(exactly = 0) { competencyRepository.delete(any()) }
            verify(exactly = 0) { competencyRepository.deleteById(any()) }
            verify(exactly = 0) { competencyEdgeRepository.delete(any()) }
            verify(exactly = 0) { competencyEdgeRepository.deleteById(any()) }
        }

        @Test
        fun `404s for a key that is not in the graph`() {
            stageGraph(competencies = listOf(competency("kotlin")))

            val exception = assertThrows<ResponseStatusException> { service.deleteCompetency("rust") }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            verify(exactly = 0) { competencyGraphVersionService.bump() }
        }
    }

    @Nested
    inner class CreateEdge {
        @Test
        fun `saves the edge and records EDGE_ADDED`() {
            stageGraph(competencies = listOf(competency("kotlin"), competency("spring")))
            every {
                competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(any(), any(), any())
            } returns false

            val response = service.createEdge(CreateCompetencyEdgeRequest("kotlin", "spring"))

            assertEquals("kotlin", response.fromKey)
            assertEquals("spring", response.toKey)
            verify(exactly = 1) { competencyEdgeRepository.save(any()) }
            verify(exactly = 1) {
                competencyGraphVersionService.recordEdgeAdded("kotlin", "spring", EdgeKind.PREREQUISITE)
            }
        }

        @Test
        fun `rejects an edge that would close a prerequisite cycle`() {
            // kotlin -> spring -> testing already exists; testing -> kotlin closes the loop.
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("spring"), competency("testing")),
                edges = listOf(edge("kotlin", "spring"), edge("spring", "testing")),
            )

            val exception = assertThrows<ResponseStatusException> {
                service.createEdge(CreateCompetencyEdgeRequest("testing", "kotlin"))
            }

            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
            assertTrue(exception.reason.orEmpty().contains("cycle"))
            verify(exactly = 0) { competencyEdgeRepository.save(any()) }
        }

        @Test
        fun `rejects a self-edge`() {
            stageGraph(competencies = listOf(competency("kotlin")))

            val exception = assertThrows<ResponseStatusException> {
                service.createEdge(CreateCompetencyEdgeRequest("kotlin", "kotlin"))
            }

            assertEquals(HttpStatus.BAD_REQUEST, exception.statusCode)
        }

        @Test
        fun `allows a RELATED edge that would be a cycle as a prerequisite`() {
            // RELATED edges neither gate nor get traversed, so a loop among them is harmless.
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("spring")),
                edges = listOf(edge("kotlin", "spring", EdgeKind.RELATED)),
            )
            every {
                competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(any(), any(), any())
            } returns false

            val response = service.createEdge(
                CreateCompetencyEdgeRequest("spring", "kotlin", EdgeKind.RELATED),
            )

            assertEquals(EdgeKind.RELATED, response.kind)
        }

        @Test
        fun `404s when an endpoint is not a live competency`() {
            stageGraph(competencies = listOf(competency("kotlin")))

            val exception = assertThrows<ResponseStatusException> {
                service.createEdge(CreateCompetencyEdgeRequest("kotlin", "rust"))
            }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
        }

        @Test
        fun `409s when the edge is already in the graph`() {
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("spring")),
                edges = listOf(edge("kotlin", "spring")),
            )

            val exception = assertThrows<ResponseStatusException> {
                service.createEdge(CreateCompetencyEdgeRequest("kotlin", "spring"))
            }

            assertEquals(HttpStatus.CONFLICT, exception.statusCode)
        }

        @Test
        fun `re-adding a removed edge reuses its row instead of inserting a duplicate`() {
            // The row survived the removal, so a second insert would hit the unique constraint.
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("spring")),
                edges = listOf(edge("kotlin", "spring")),
                extraChanges = listOf(
                    CompetencyGraphChange(
                        version = 2,
                        changeType = ChangeType.EDGE_REMOVED,
                        fromKey = "kotlin",
                        toKey = "spring",
                        edgeKind = EdgeKind.PREREQUISITE,
                    ),
                ),
            )
            every {
                competencyEdgeRepository.existsByFromKeyAndToKeyAndKind("kotlin", "spring", EdgeKind.PREREQUISITE)
            } returns true

            service.createEdge(CreateCompetencyEdgeRequest("kotlin", "spring"))

            verify(exactly = 0) { competencyEdgeRepository.save(any()) }
            verify(exactly = 1) {
                competencyGraphVersionService.recordEdgeAdded("kotlin", "spring", EdgeKind.PREREQUISITE)
            }
        }

        @Test
        fun `a removed edge does not veto a new one as a cycle`() {
            // kotlin -> spring was removed, so spring -> kotlin is no longer a loop.
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("spring")),
                edges = listOf(edge("kotlin", "spring")),
                extraChanges = listOf(
                    CompetencyGraphChange(
                        version = 2,
                        changeType = ChangeType.EDGE_REMOVED,
                        fromKey = "kotlin",
                        toKey = "spring",
                        edgeKind = EdgeKind.PREREQUISITE,
                    ),
                ),
            )
            every {
                competencyEdgeRepository.existsByFromKeyAndToKeyAndKind("spring", "kotlin", EdgeKind.PREREQUISITE)
            } returns false

            val response = service.createEdge(CreateCompetencyEdgeRequest("spring", "kotlin"))

            assertEquals("spring", response.fromKey)
        }
    }

    @Nested
    inner class DeleteEdge {
        @Test
        fun `records EDGE_REMOVED without deleting the row or either endpoint`() {
            stageGraph(
                competencies = listOf(competency("kotlin"), competency("spring")),
                edges = listOf(edge("kotlin", "spring")),
            )

            service.deleteEdge("kotlin", "spring", EdgeKind.PREREQUISITE)

            verify(exactly = 1) {
                competencyGraphVersionService.recordEdgeRemoved("kotlin", "spring", EdgeKind.PREREQUISITE)
            }
            verify(exactly = 0) { competencyEdgeRepository.delete(any()) }
            verify(exactly = 0) { competencyGraphVersionService.recordNodeRemoved(any()) }
        }

        @Test
        fun `404s for an edge that is not in the graph`() {
            stageGraph(competencies = listOf(competency("kotlin"), competency("spring")))

            val exception = assertThrows<ResponseStatusException> {
                service.deleteEdge("kotlin", "spring", EdgeKind.PREREQUISITE)
            }

            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            verify(exactly = 0) { competencyGraphVersionService.bump() }
        }
    }
}
