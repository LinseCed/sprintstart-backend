package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.HireCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposedStarterTaskSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.RankedStarterTaskSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.StarterWorkOutcome
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlin.test.assertTrue

class StarterWorkTaskProposalServiceTest {
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val competencyRepository: CompetencyRepository = mockk()
    private val competencyEdgeRepository: CompetencyEdgeRepository = mockk()
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository = mockk()
    private val userCompetencyStateRepository: UserCompetencyStateRepository = mockk()
    private val competencyGraphVersionService: CompetencyGraphVersionService = mockk(relaxed = true)
    private val userApi: UserApi = mockk()
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service = StarterWorkTaskProposalService(
        onboardingAiClient,
        competencyRepository,
        competencyEdgeRepository,
        starterWorkTaskProposalRepository,
        userCompetencyStateRepository,
        competencyGraphVersionService,
        userApi,
        transactionManager,
    )

    @Nested
    inner class Generate {
        @Test
        fun `persists proposed tasks as PROPOSED rows`() = runTest {
            every { starterWorkTaskProposalRepository.findAllByStatusIn(any()) } returns emptyList()
            every { competencyRepository.findAll() } returns emptyList()
            coEvery { onboardingAiClient.proposeStarterWork(any(), any()) } returns
                StarterWorkOutcome(
                    status = "proposed",
                    tasks = listOf(
                        ProposedStarterTaskSchema(
                            sourceId = "github:org/repo:ISSUE:1",
                            title = "Fix typo",
                            summary = "Fix a typo in the README.",
                            competencyKeys = listOf("docs"),
                            rationale = "Small, well-scoped.",
                        ),
                    ),
                )
            val slot = slot<StarterWorkTaskProposal>()
            every { starterWorkTaskProposalRepository.save(capture(slot)) } answers { slot.captured }

            val result = service.generate()

            assertEquals("github:org/repo:ISSUE:1", slot.captured.sourceId)
            assertEquals("Fix typo", slot.captured.title)
            assertEquals(listOf("docs"), slot.captured.competencyKeys)
            assertEquals(ProposalStatus.PROPOSED, slot.captured.status)
            assertEquals(1, result.tasksProposed)
            assertEquals("proposed", result.status)
        }

        @Test
        fun `sends pool source ids excluding rejected and the live competency keys`() = runTest {
            val pooledStatuses = listOf(ProposalStatus.PROPOSED, ProposalStatus.APPROVED)
            every { starterWorkTaskProposalRepository.findAllByStatusIn(pooledStatuses) } returns
                listOf(
                    StarterWorkTaskProposal(sourceId = "s1", title = "t1", status = ProposalStatus.PROPOSED),
                    StarterWorkTaskProposal(sourceId = "s2", title = "t2", status = ProposalStatus.APPROVED),
                )
            every { competencyRepository.findAll() } returns
                listOf(Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL))
            val sourceIdsSlot = slot<List<String>>()
            val keysSlot = slot<List<String>>()
            coEvery {
                onboardingAiClient.proposeStarterWork(capture(sourceIdsSlot), capture(keysSlot))
            } returns StarterWorkOutcome(status = "unchanged")

            service.generate()

            assertEquals(listOf("s1", "s2"), sourceIdsSlot.captured)
            assertEquals(listOf("kotlin"), keysSlot.captured)
        }
    }

    @Nested
    inner class ListProposed {
        @Test
        fun `returns PROPOSED tasks`() {
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.PROPOSED) } returns
                listOf(StarterWorkTaskProposal(sourceId = "s1", title = "t1"))

            val result = service.listProposed()

            assertEquals(1, result.tasks.size)
        }
    }

    @Nested
    inner class Approve {
        @Test
        fun `creates a CONTRIBUTION competency and prerequisite edges for known keys`() {
            val proposal = StarterWorkTaskProposal(
                sourceId = "github:org/repo:ISSUE:1",
                title = "Fix typo",
                summary = "Fix a typo",
                competencyKeys = mutableListOf("docs", "unknown-key"),
            )
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            every { competencyRepository.existsByKey("github-org-repo-issue-1") } returns false
            every { competencyRepository.save(any<Competency>()) } answers { firstArg() }
            every { competencyRepository.findAllByKeyIn(listOf("docs", "unknown-key")) } returns
                listOf(Competency(key = "docs", label = "Docs", kind = CompetencyKind.SKILL))
            every {
                competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(
                    "docs",
                    "github-org-repo-issue-1",
                    EdgeKind.PREREQUISITE,
                )
            } returns false
            every { competencyEdgeRepository.save(any()) } answers { firstArg() }

            val result = service.approve(proposal.id)

            assertEquals(ProposalStatus.APPROVED, proposal.status)
            assertEquals(ProposalStatus.APPROVED, result.status)
            verify(exactly = 1) {
                competencyRepository.save(
                    match<Competency> { it.key == "github-org-repo-issue-1" && it.kind == CompetencyKind.CONTRIBUTION },
                )
            }
            verify(exactly = 1) { competencyGraphVersionService.recordNodeAdded("github-org-repo-issue-1") }
            verify(exactly = 1) {
                competencyEdgeRepository.save(
                    match { it.fromKey == "docs" && it.toKey == "github-org-repo-issue-1" },
                )
            }
            verify(exactly = 1) { competencyGraphVersionService.bump() }
        }

        @Test
        fun `does not recreate the contribution competency if it already exists`() {
            val proposal = StarterWorkTaskProposal(sourceId = "github:org/repo:ISSUE:2", title = "Existing")
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            every { competencyRepository.existsByKey("github-org-repo-issue-2") } returns true
            every { competencyRepository.findAllByKeyIn(emptyList()) } returns emptyList()

            service.approve(proposal.id)

            verify(exactly = 0) { competencyRepository.save(any()) }
            verify(exactly = 0) { competencyGraphVersionService.recordNodeAdded(any()) }
        }

        @Test
        fun `throws 404 when no proposal matches`() {
            val id = UUID.randomUUID()
            every { starterWorkTaskProposalRepository.findById(id) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.approve(id) }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }

        @Test
        fun `throws 409 when the proposal was already decided`() {
            val proposal = StarterWorkTaskProposal(sourceId = "s1", title = "t1", status = ProposalStatus.REJECTED)
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)

            val ex = assertThrows<ResponseStatusException> { service.approve(proposal.id) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }
    }

    @Nested
    inner class Reject {
        @Test
        fun `marks the proposal REJECTED without touching the graph`() {
            val proposal = StarterWorkTaskProposal(sourceId = "s1", title = "t1")
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)

            val result = service.reject(proposal.id, "not relevant")

            assertEquals(ProposalStatus.REJECTED, proposal.status)
            assertEquals("not relevant", proposal.rejectionReason)
            assertEquals(ProposalStatus.REJECTED, result.status)
            verify(exactly = 0) { competencyRepository.save(any()) }
        }

        @Test
        fun `reason defaults to null`() {
            val proposal = StarterWorkTaskProposal(sourceId = "s1", title = "t1")
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)

            service.reject(proposal.id, null)

            assertNull(proposal.rejectionReason)
        }
    }

    @Nested
    inner class MatchForUser {
        private val userId = UUID.randomUUID()

        @Test
        fun `ranks the approved pool for the resolved user`() = runTest {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns
                listOf(
                    UserCompetencyState(
                        userId = userId,
                        competencyKey = "kotlin",
                        level = 3,
                        source = CompetencySource.VERIFIED,
                    ),
                )
            every { competencyRepository.findAllByKeyIn(listOf("kotlin")) } returns
                listOf(Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL))
            val pooled = StarterWorkTaskProposal(
                sourceId = "s1",
                title = "Task",
                competencyKeys = mutableListOf("kotlin"),
                status = ProposalStatus.APPROVED,
            )
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns listOf(pooled)
            val hireSlot = slot<List<HireCompetencySchema>>()
            coEvery { onboardingAiClient.matchHireToPool(capture(hireSlot), any()) } returns
                listOf(
                    RankedStarterTaskSchema(
                        task = ProposedStarterTaskSchema(sourceId = "s1", title = "Task"),
                        score = 1.0,
                        matchedCompetencyKeys = listOf("kotlin"),
                    ),
                )

            val result = service.matchForUser("auth-1")

            assertEquals(listOf("kotlin"), hireSlot.captured.map { it.key })
            assertEquals(1, result.size)
            assertEquals("s1", result[0].task.sourceId)
            assertEquals(1.0, result[0].score)
        }

        @Test
        fun `excludes level-0 (unplaced) ledger entries from the hire's competencies`() = runTest {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns
                listOf(
                    UserCompetencyState(
                        userId = userId,
                        competencyKey = "kotlin",
                        level = 0,
                        source = CompetencySource.ASSESSED,
                    ),
                    UserCompetencyState(
                        userId = userId,
                        competencyKey = "git",
                        level = 2,
                        source = CompetencySource.VERIFIED,
                    ),
                )
            every { competencyRepository.findAllByKeyIn(listOf("git")) } returns
                listOf(Competency(key = "git", label = "Git", kind = CompetencyKind.SKILL))
            val pooled = StarterWorkTaskProposal(
                sourceId = "s1",
                title = "Task",
                competencyKeys = mutableListOf("git"),
                status = ProposalStatus.APPROVED,
            )
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns listOf(pooled)
            val hireSlot = slot<List<HireCompetencySchema>>()
            coEvery { onboardingAiClient.matchHireToPool(capture(hireSlot), any()) } returns emptyList()

            service.matchForUser("auth-1")

            assertEquals(listOf("git"), hireSlot.captured.map { it.key })
        }

        @Test
        fun `skips the AI call when the pool is empty`() = runTest {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
            every { competencyRepository.findAllByKeyIn(emptyList()) } returns emptyList()
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns emptyList()

            val result = service.matchForUser("auth-1")

            assertTrue(result.isEmpty())
            coVerify(exactly = 0) { onboardingAiClient.matchHireToPool(any(), any()) }
        }

        @Test
        fun `throws 404 when no user matches the auth id`() = runTest {
            every { userApi.getUserIdByAuthId("missing") } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.matchForUser("missing") }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }
    }
}
