package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AiProgressEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.GraphProposalOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.GraphProvenanceSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposedCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposedEdgeSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyProposal
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompetencyProposalServiceTest {
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val competencyRepository: CompetencyRepository = mockk()
    private val competencyEdgeRepository: CompetencyEdgeRepository = mockk()
    private val competencyProposalRepository: CompetencyProposalRepository = mockk()
    private val competencyEdgeProposalRepository: CompetencyEdgeProposalRepository = mockk()
    private val competencyGraphVersionService: CompetencyGraphVersionService = mockk(relaxed = true)
    private val json: Json = Json { ignoreUnknownKeys = true }
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service = CompetencyProposalService(
        onboardingAiClient,
        competencyRepository,
        competencyEdgeRepository,
        competencyProposalRepository,
        competencyEdgeProposalRepository,
        competencyGraphVersionService,
        json,
        transactionManager,
    )

    @Nested
    inner class Generate {
        @Test
        fun `persists proposed competencies and edges as PROPOSED rows`() = runTest {
            every { competencyRepository.findAll() } returns emptyList()
            every { competencyEdgeRepository.findAll() } returns emptyList()
            every { competencyProposalRepository.findTopByOrderByCreatedAtDesc() } returns null
            every { competencyEdgeProposalRepository.findTopByOrderByCreatedAtDesc() } returns null
            every { competencyRepository.existsByKey(any()) } returns false
            every { competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(any(), any(), any()) } returns false
            coEvery { onboardingAiClient.proposeCompetencyGraph(any(), any(), any()) } returns
                GraphProposalOutcome(
                    status = "proposed",
                    competencies = listOf(
                        ProposedCompetencySchema(key = "kotlin", label = "Kotlin", kind = "SKILL"),
                    ),
                    edges = listOf(
                        ProposedEdgeSchema(fromKey = "kotlin", toKey = "domain-model", rationale = "needed for it"),
                    ),
                    provenance = GraphProvenanceSchema(corpusFingerprint = "fp-1"),
                )
            val competencySlot = slot<CompetencyProposal>()
            every { competencyProposalRepository.save(capture(competencySlot)) } answers { competencySlot.captured }
            val edgeSlot = slot<CompetencyEdgeProposal>()
            every { competencyEdgeProposalRepository.save(capture(edgeSlot)) } answers { edgeSlot.captured }

            val result = service.generate()

            assertEquals("kotlin", competencySlot.captured.key)
            assertEquals(CompetencyKind.SKILL, competencySlot.captured.kind)
            assertEquals("fp-1", competencySlot.captured.corpusFingerprint)
            assertEquals(ProposalStatus.PROPOSED, competencySlot.captured.status)
            assertEquals("kotlin", edgeSlot.captured.fromKey)
            assertEquals("domain-model", edgeSlot.captured.toKey)
            assertEquals("needed for it", edgeSlot.captured.rationale)
            assertEquals(1, result.competenciesProposed)
            assertEquals(1, result.edgesProposed)
            assertEquals("proposed", result.status)
        }

        @Test
        fun `sends the live graph and most recent fingerprint to the AI client`() = runTest {
            every { competencyRepository.findAll() } returns listOf(
                Competency(key = "git", label = "Git", kind = CompetencyKind.SKILL),
            )
            every { competencyEdgeRepository.findAll() } returns emptyList()
            every { competencyProposalRepository.findTopByOrderByCreatedAtDesc() } returns
                CompetencyProposal(key = "x", label = "X", kind = CompetencyKind.SKILL, corpusFingerprint = "fp-old")
            every { competencyEdgeProposalRepository.findTopByOrderByCreatedAtDesc() } returns null
            val activeSlot = slot<List<ActiveCompetencySchema>>()
            coEvery {
                onboardingAiClient.proposeCompetencyGraph(capture(activeSlot), any(), eq("fp-old"))
            } returns GraphProposalOutcome(status = "unchanged")

            service.generate()

            assertEquals(listOf("git"), activeSlot.captured.map { it.key })
        }
    }

    @Nested
    inner class StreamGenerate {
        private fun stubActiveState() {
            every { competencyRepository.findAll() } returns emptyList()
            every { competencyEdgeRepository.findAll() } returns emptyList()
            every { competencyProposalRepository.findTopByOrderByCreatedAtDesc() } returns null
            every { competencyEdgeProposalRepository.findTopByOrderByCreatedAtDesc() } returns null
        }

        private fun proposedOutcome() = GraphProposalOutcome(
            status = "proposed",
            competencies = listOf(ProposedCompetencySchema(key = "kotlin", label = "Kotlin", kind = "SKILL")),
            edges = listOf(ProposedEdgeSchema(fromKey = "kotlin", toKey = "git", rationale = "needed")),
            provenance = GraphProvenanceSchema(corpusFingerprint = "fp-1"),
        )

        private fun doneEvent(outcome: GraphProposalOutcome) = AiProgressEvent(
            type = "done",
            operation = "competency_graph",
            result = json.encodeToJsonElement(outcome),
        )

        @Test
        fun `relays the events and persists the proposals on done`() = runTest {
            stubActiveState()
            every { competencyRepository.existsByKey(any()) } returns false
            every { competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(any(), any(), any()) } returns false
            every { competencyProposalRepository.save(any()) } answers { firstArg() }
            every { competencyEdgeProposalRepository.save(any()) } answers { firstArg() }
            every { onboardingAiClient.streamCompetencyGraph(any(), any(), any()) } returns flowOf(
                AiProgressEvent(type = "stage", operation = "competency_graph", stage = "retrieving", label = "…"),
                AiProgressEvent(type = "item", operation = "competency_graph", label = "Competency: Kotlin"),
                doneEvent(proposedOutcome()),
            )

            val events = service.streamGenerate().toList()

            assertEquals(listOf("stage", "item", "done"), events.map { it.type })
            verify(exactly = 1) { competencyProposalRepository.save(any()) }
            verify(exactly = 1) { competencyEdgeProposalRepository.save(any()) }
        }

        @Test
        fun `an item re-gated away on persist becomes a warning before the done`() = runTest {
            stubActiveState()
            // The competency is already live -> the backend gate skips it and must announce it.
            every { competencyRepository.existsByKey("kotlin") } returns true
            every { competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(any(), any(), any()) } returns false
            every { competencyEdgeProposalRepository.save(any()) } answers { firstArg() }
            every { onboardingAiClient.streamCompetencyGraph(any(), any(), any()) } returns flowOf(
                doneEvent(proposedOutcome()),
            )

            val events = service.streamGenerate().toList()

            // A warning for the skipped node lands before the terminal done; the edge still persists.
            assertEquals(listOf("warning", "done"), events.map { it.type })
            verify(exactly = 0) { competencyProposalRepository.save(any()) }
            verify(exactly = 1) { competencyEdgeProposalRepository.save(any()) }
        }

        @Test
        fun `a stream failure becomes a synthesised terminal error`() = runTest {
            stubActiveState()
            every { onboardingAiClient.streamCompetencyGraph(any(), any(), any()) } returns
                flow { throw RuntimeException("ai down") }

            val events = service.streamGenerate().toList()

            assertEquals("error", events.last().type)
        }
    }

    @Nested
    inner class ListProposed {
        @Test
        fun `returns PROPOSED competencies and edges`() {
            every { competencyProposalRepository.findAllByStatus(ProposalStatus.PROPOSED) } returns
                listOf(CompetencyProposal(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL))
            every { competencyEdgeProposalRepository.findAllByStatus(ProposalStatus.PROPOSED) } returns
                listOf(CompetencyEdgeProposal(fromKey = "kotlin", toKey = "domain-model"))

            val result = service.listProposed()

            assertEquals(1, result.competencies.size)
            assertEquals(1, result.edges.size)
        }
    }

    @Nested
    inner class ApproveCompetency {
        @Test
        fun `creates the competency and records the graph change`() {
            val proposal = CompetencyProposal(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
            every { competencyProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            every { competencyRepository.save(any<Competency>()) } answers { firstArg() }

            val result = service.approveCompetency(proposal.id)

            assertEquals(ProposalStatus.APPROVED, proposal.status)
            assertEquals(ProposalStatus.APPROVED, result.status)
            verify(exactly = 1) { competencyRepository.save(any()) }
            verify(exactly = 1) { competencyGraphVersionService.recordNodeAdded("kotlin") }
            verify(exactly = 1) { competencyGraphVersionService.bump() }
        }

        @Test
        fun `throws 404 when no proposal matches`() {
            val id = UUID.randomUUID()
            every { competencyProposalRepository.findById(id) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.approveCompetency(id) }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }

        @Test
        fun `throws 409 when the proposal was already decided`() {
            val proposal = CompetencyProposal(
                key = "kotlin",
                label = "Kotlin",
                kind = CompetencyKind.SKILL,
                status = ProposalStatus.APPROVED,
            )
            every { competencyProposalRepository.findById(proposal.id) } returns Optional.of(proposal)

            val ex = assertThrows<ResponseStatusException> { service.approveCompetency(proposal.id) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }
    }

    @Nested
    inner class RejectCompetency {
        @Test
        fun `marks the proposal REJECTED without touching the graph`() {
            val proposal = CompetencyProposal(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
            every { competencyProposalRepository.findById(proposal.id) } returns Optional.of(proposal)

            val result = service.rejectCompetency(proposal.id, "not relevant")

            assertEquals(ProposalStatus.REJECTED, proposal.status)
            assertEquals("not relevant", proposal.rejectionReason)
            assertEquals(ProposalStatus.REJECTED, result.status)
            verify(exactly = 0) { competencyRepository.save(any()) }
        }
    }

    @Nested
    inner class ApproveEdge {
        @Test
        fun `creates the edge when both endpoints already exist`() {
            val proposal = CompetencyEdgeProposal(fromKey = "kotlin", toKey = "domain-model")
            every { competencyEdgeProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            every { competencyRepository.existsByKey("kotlin") } returns true
            every { competencyRepository.existsByKey("domain-model") } returns true
            every { competencyEdgeRepository.save(any()) } answers { firstArg() }

            val result = service.approveEdge(proposal.id)

            assertEquals(ProposalStatus.APPROVED, proposal.status)
            assertEquals(ProposalStatus.APPROVED, result.status)
            verify(exactly = 1) {
                competencyGraphVersionService.recordEdgeAdded("kotlin", "domain-model", EdgeKind.PREREQUISITE)
            }
        }

        @Test
        fun `throws 409 when an endpoint is not yet a live competency`() {
            val proposal = CompetencyEdgeProposal(fromKey = "kotlin", toKey = "not-yet-approved")
            every { competencyEdgeProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            every { competencyRepository.existsByKey("kotlin") } returns true
            every { competencyRepository.existsByKey("not-yet-approved") } returns false

            val ex = assertThrows<ResponseStatusException> { service.approveEdge(proposal.id) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
            verify(exactly = 0) { competencyEdgeRepository.save(any()) }
        }
    }

    @Nested
    inner class ApproveBatch {
        @Test
        fun `creates nodes before edges and bumps the version exactly once`() {
            val node = CompetencyProposal(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
            val edge = CompetencyEdgeProposal(fromKey = "kotlin", toKey = "domain-model")
            every { competencyProposalRepository.findById(node.id) } returns Optional.of(node)
            every { competencyEdgeProposalRepository.findById(edge.id) } returns Optional.of(edge)
            // "domain-model" is already live; "kotlin" only becomes live inside this batch, so
            // the endpoint check passing at all depends on nodes being created first.
            every { competencyRepository.existsByKey(any()) } returns true
            every { competencyRepository.save(any<Competency>()) } answers { firstArg() }
            every { competencyEdgeRepository.save(any()) } answers { firstArg() }
            every { competencyGraphVersionService.bump() } returns 7

            val result = service.approveBatch(listOf(node.id), listOf(edge.id))

            assertEquals(1, result.competenciesApproved)
            assertEquals(1, result.edgesApproved)
            assertEquals(7, result.graphVersion)
            assertEquals(ProposalStatus.APPROVED, node.status)
            assertEquals(ProposalStatus.APPROVED, edge.status)
            // One bump for the whole batch is the entire reason this endpoint exists: it is what
            // lets GraphChangeClassifier see the edge's target as newly introduced and classify
            // the subgraph ADDITIVE instead of STRUCTURAL.
            verify(exactly = 1) { competencyGraphVersionService.bump() }
            verify(exactly = 1) { competencyGraphVersionService.recordNodeAdded("kotlin") }
            verify(exactly = 1) {
                competencyGraphVersionService.recordEdgeAdded("kotlin", "domain-model", EdgeKind.PREREQUISITE)
            }
        }

        @Test
        fun `a node and the edge into it classify ADDITIVE when batched`() {
            // The regression this endpoint exists for, asserted against the real classifier
            // rather than a mock: approved separately these are two versions, and the edge's
            // version is STRUCTURAL, so it is held back and the node can re-lock later.
            val changes = listOf(
                CompetencyGraphChange(version = 3, changeType = ChangeType.NODE_ADDED, competencyKey = "spring"),
                CompetencyGraphChange(
                    version = 3,
                    changeType = ChangeType.EDGE_ADDED,
                    fromKey = "kotlin",
                    toKey = "spring",
                    edgeKind = EdgeKind.PREREQUISITE,
                ),
            )
            val competencies = mapOf(
                "kotlin" to Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL),
                "spring" to Competency(key = "spring", label = "Spring", kind = CompetencyKind.SKILL),
            )

            val classification = GraphChangeClassifier().classify(changes, competencies)

            assertEquals(ChangeClassification.ADDITIVE, classification)
        }

        @Test
        fun `rolls back the whole batch when one edge endpoint is missing`() {
            val edge = CompetencyEdgeProposal(fromKey = "kotlin", toKey = "not-approved")
            every { competencyEdgeProposalRepository.findById(edge.id) } returns Optional.of(edge)
            every { competencyRepository.existsByKey("kotlin") } returns true
            every { competencyRepository.existsByKey("not-approved") } returns false

            val ex = assertThrows<ResponseStatusException> { service.approveBatch(emptyList(), listOf(edge.id)) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
            // A partially applied batch is exactly the split version this avoids, so nothing is
            // bumped -- the transaction rolls the rest back.
            verify(exactly = 0) { competencyGraphVersionService.bump() }
        }

        @Test
        fun `409s when a proposal in the batch was already decided`() {
            val node = CompetencyProposal(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL)
            node.status = ProposalStatus.APPROVED
            every { competencyProposalRepository.findById(node.id) } returns Optional.of(node)

            val ex = assertThrows<ResponseStatusException> { service.approveBatch(listOf(node.id), emptyList()) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
            verify(exactly = 0) { competencyGraphVersionService.bump() }
        }
    }

    @Nested
    inner class RejectEdge {
        @Test
        fun `marks the proposal REJECTED without touching the graph`() {
            val proposal = CompetencyEdgeProposal(fromKey = "kotlin", toKey = "domain-model")
            every { competencyEdgeProposalRepository.findById(proposal.id) } returns Optional.of(proposal)

            val result = service.rejectEdge(proposal.id, null)

            assertEquals(ProposalStatus.REJECTED, proposal.status)
            assertNull(proposal.rejectionReason)
            assertEquals(ProposalStatus.REJECTED, result.status)
            verify(exactly = 0) { competencyEdgeRepository.save(any()) }
        }
    }
}
