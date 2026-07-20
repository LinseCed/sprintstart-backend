package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.ingestion.external.RepositoryResponsiveness
import com.sprintstart.sprintstartbackend.ingestion.external.TaskSourceArtifact
import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposedStarterTaskSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.StarterWorkOutcome
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.GithubHistoryPrior
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
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
import kotlin.test.assertContains
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
    private val githubHistoryPriorService: GithubHistoryPriorService = mockk()
    private val artifactIngestionApi: ArtifactIngestionApi = mockk()
    private val userApi: UserApi = mockk()
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service = StarterWorkTaskProposalService(
        onboardingAiClient,
        competencyRepository,
        competencyEdgeRepository,
        starterWorkTaskProposalRepository,
        userCompetencyStateRepository,
        competencyGraphVersionService,
        githubHistoryPriorService,
        artifactIngestionApi,
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
        private val projectId = UUID.randomUUID()

        private fun heldCompetency(key: String, level: Int = 3) =
            UserCompetencyState(
                userId = userId,
                competencyKey = key,
                level = level,
                source = CompetencySource.VERIFIED,
            )

        private fun pooledTask(sourceId: String, title: String, vararg keys: String) =
            StarterWorkTaskProposal(
                sourceId = sourceId,
                title = title,
                competencyKeys = keys.toMutableList(),
                status = ProposalStatus.APPROVED,
            )

        private fun noHistory() {
            every { githubHistoryPriorService.getPrior(userId) } returns null
            every { artifactIngestionApi.getRepositoryResponsiveness(projectId) } returns emptyList()
            every { artifactIngestionApi.getTaskSource(any()) } returns null
        }

        @Test
        fun `ranks the approved pool for the resolved user`() {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns
                listOf(heldCompetency("kotlin"))
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns
                listOf(pooledTask("github:acme/api:ISSUE:1", "Task", "kotlin"))
            noHistory()

            val result = service.matchForUser("auth-1", projectId)

            assertEquals(1, result.size)
            assertEquals("github:acme/api:ISSUE:1", result[0].task.sourceId)
            assertEquals(listOf("kotlin"), result[0].matchedCompetencyKeys)
            // A suggestion nobody can interrogate is an instruction.
            assertTrue(result[0].reasons.isNotEmpty())
        }

        @Test
        fun `excludes level-0 (unplaced) ledger entries from the hire's competencies`() {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns
                listOf(
                    UserCompetencyState(
                        userId = userId,
                        competencyKey = "kotlin",
                        level = 0,
                        source = CompetencySource.ASSESSED,
                    ),
                    heldCompetency("git", level = 2),
                )
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns
                listOf(pooledTask("github:acme/api:ISSUE:1", "Unplaced", "kotlin"))
            noHistory()

            val result = service.matchForUser("auth-1", projectId)

            // "kotlin" is on the ledger but unplaced, so it must not count as competence held.
            assertEquals(emptyList(), result[0].matchedCompetencyKeys)
        }

        @Test
        fun `a hire with strong repo history ranks familiar work above unfamiliar work`() {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
            every { githubHistoryPriorService.getPrior(userId) } returns
                GithubHistoryPrior(
                    userId = userId,
                    signals = mutableMapOf("repo:acme/api" to 6, "label:bug" to 4),
                )
            every { artifactIngestionApi.getRepositoryResponsiveness(projectId) } returns emptyList()
            every { artifactIngestionApi.getTaskSource("github:acme/api:ISSUE:1") } returns
                TaskSourceArtifact(title = null, body = null, labels = listOf("bug"), sourceUrl = null)
            every { artifactIngestionApi.getTaskSource("github:acme/web:ISSUE:2") } returns
                TaskSourceArtifact(title = null, body = null, labels = listOf("feature"), sourceUrl = null)
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns
                listOf(
                    pooledTask("github:acme/web:ISSUE:2", "Elsewhere"),
                    pooledTask("github:acme/api:ISSUE:1", "Familiar"),
                )

            val result = service.matchForUser("auth-1", projectId)

            assertEquals("Familiar", result.first().task.title)
            assertContains(result.first().reasons.joinToString(), "acme/api")
        }

        @Test
        fun `a hire with no history still gets a ranked pool rather than nothing`() {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
            every { githubHistoryPriorService.getPrior(userId) } returns null
            every { artifactIngestionApi.getRepositoryResponsiveness(projectId) } returns emptyList()
            every { artifactIngestionApi.getTaskSource("github:acme/api:ISSUE:1") } returns
                TaskSourceArtifact(title = null, body = null, labels = listOf("refactor"), sourceUrl = null)
            every { artifactIngestionApi.getTaskSource("github:acme/api:ISSUE:2") } returns
                TaskSourceArtifact(title = null, body = null, labels = listOf("documentation"), sourceUrl = null)
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns
                listOf(
                    pooledTask("github:acme/api:ISSUE:1", "Refactor the scheduler"),
                    pooledTask("github:acme/api:ISSUE:2", "Fix a typo"),
                )

            val result = service.matchForUser("auth-1", projectId)

            // No consent and no ledger is "no evidence", never "beginner" -- but a forgiving first
            // task is still the better default.
            assertEquals("Fix a typo", result.first().task.title)
        }

        @Test
        fun `a slow repository demotes its task without hiding it`() {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
            every { githubHistoryPriorService.getPrior(userId) } returns null
            every { artifactIngestionApi.getRepositoryResponsiveness(projectId) } returns
                listOf(
                    RepositoryResponsiveness(
                        repositoryFullName = "acme/api",
                        medianHoursToFirstResponse = 300,
                        answeredCount = 2,
                        unansweredCount = 5,
                    ),
                )
            every { artifactIngestionApi.getTaskSource(any()) } returns null
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns
                listOf(pooledTask("github:acme/api:ISSUE:1", "Slow repo task"))

            val result = service.matchForUser("auth-1", projectId)

            // Still present -- a stale owner is a signal to a PM, not a reason to bury real work.
            assertEquals(1, result.size)
            assertContains(result[0].reasons.joinToString(), "reviews here take")
        }

        @Test
        fun `returns nothing when the pool is empty`() {
            every { userApi.getUserIdByAuthId("auth-1") } returns Optional.of(userId)
            every { starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED) } returns emptyList()

            val result = service.matchForUser("auth-1", projectId)

            assertTrue(result.isEmpty())
        }

        @Test
        fun `throws 404 when no user matches the auth id`() {
            every { userApi.getUserIdByAuthId("missing") } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.matchForUser("missing", projectId) }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }
    }
}
