package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.GraphProposalOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.GraphProvenanceSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposedCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposedEdgeSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal
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
import kotlinx.coroutines.test.runTest
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
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service = CompetencyProposalService(
        onboardingAiClient,
        competencyRepository,
        competencyEdgeRepository,
        competencyProposalRepository,
        competencyEdgeProposalRepository,
        competencyGraphVersionService,
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
