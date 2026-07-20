package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGoal
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGoalRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserGoalServiceTest {
    private val userGoalRepository: UserGoalRepository = mockk(relaxed = true)
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository = mockk()
    private val competencyRepository: CompetencyRepository = mockk()
    private val userApi: UserApi = mockk()
    private val service = UserGoalService(
        userGoalRepository,
        starterWorkTaskProposalRepository,
        competencyRepository,
        userApi,
    )

    private val userId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()
    private val authId = "auth-1"

    // The key approval derives from this sourceId, which is what the goal must resolve through.
    private val sourceId = "github:acme/repo:ISSUE:42"
    private val contributionKey = "github-acme-repo-issue-42"

    private fun approvedProposal() = StarterWorkTaskProposal(
        sourceId = sourceId,
        title = "Fix the login redirect",
        summary = "A small, well-scoped bug",
        sourceUrl = "https://github.com/acme/repo/issues/42",
    ).apply { status = ProposalStatus.APPROVED }

    private fun contributionNode() = Competency(
        key = contributionKey,
        label = "Fix the login redirect",
        kind = CompetencyKind.CONTRIBUTION,
    )

    private fun stageUser() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
        // A relaxed mock returns a bare Object from the generic save(S): S, which the checkcast
        // Kotlin inserts at the call site rejects -- echo the argument back instead.
        every { userGoalRepository.save(any()) } answers { firstArg() }
    }

    @Nested
    inner class ClaimForMe {
        @Test
        fun `resolves the contribution node through the derived key, not the label`() {
            stageUser()
            val proposal = approvedProposal()
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            // Renamed by a PM since approval (#50 made labels editable). Resolving on the label
            // would fail here; resolving on the derived key must not care.
            every { competencyRepository.findByKey(contributionKey) } returns
                contributionNode().apply { label = "Login redirect fix (renamed)" }
            every { userGoalRepository.findByUserIdAndProjectId(userId, projectId) } returns null

            val result = service.claimForMe(authId, projectId, proposal.id)

            assertEquals(contributionKey, result.competencyKey)
            assertEquals("Login redirect fix (renamed)", result.label)
            verify { competencyRepository.findByKey(contributionKey) }
        }

        @Test
        fun `persists the goal against the user and project`() {
            stageUser()
            val proposal = approvedProposal()
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            every { competencyRepository.findByKey(contributionKey) } returns contributionNode()
            every { userGoalRepository.findByUserIdAndProjectId(userId, projectId) } returns null
            val saved = slot<UserGoal>()
            every { userGoalRepository.save(capture(saved)) } answers { saved.captured }

            service.claimForMe(authId, projectId, proposal.id)

            assertEquals(userId, saved.captured.userId)
            assertEquals(projectId, saved.captured.projectId)
            assertEquals(contributionKey, saved.captured.competencyKey)
            assertEquals(proposal.id, saved.captured.sourceProposalId)
        }

        @Test
        fun `replaces an existing goal rather than adding a second one`() {
            stageUser()
            val proposal = approvedProposal()
            val existing = UserGoal(
                userId = userId,
                projectId = projectId,
                competencyKey = "some-older-contribution",
            )
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            every { competencyRepository.findByKey(contributionKey) } returns contributionNode()
            every { userGoalRepository.findByUserIdAndProjectId(userId, projectId) } returns existing
            val saved = slot<UserGoal>()
            every { userGoalRepository.save(capture(saved)) } answers { saved.captured }

            service.claimForMe(authId, projectId, proposal.id)

            assertEquals(existing.id, saved.captured.id)
            assertEquals(contributionKey, saved.captured.competencyKey)
        }

        @Test
        fun `409s for a task the PM has not approved`() {
            stageUser()
            val proposal = approvedProposal().apply { status = ProposalStatus.PROPOSED }
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)

            val exception = assertThrows<ResponseStatusException> {
                service.claimForMe(authId, projectId, proposal.id)
            }

            // A PM curates which tasks exist; a hire may pick from that set, not extend it.
            assertEquals(HttpStatus.CONFLICT, exception.statusCode)
            verify(exactly = 0) { userGoalRepository.save(any()) }
        }

        @Test
        fun `404s when the approved task has no contribution node`() {
            stageUser()
            val proposal = approvedProposal()
            every { starterWorkTaskProposalRepository.findById(proposal.id) } returns Optional.of(proposal)
            every { competencyRepository.findByKey(contributionKey) } returns null

            val exception = assertThrows<ResponseStatusException> {
                service.claimForMe(authId, projectId, proposal.id)
            }

            // Claiming a goal that isn't a node would leave the path aiming at nothing.
            assertEquals(HttpStatus.NOT_FOUND, exception.statusCode)
            verify(exactly = 0) { userGoalRepository.save(any()) }
        }
    }

    @Nested
    inner class FindForUser {
        @Test
        fun `returns the goal when its node is still in the graph`() {
            every { userGoalRepository.findByUserIdAndProjectId(userId, projectId) } returns
                UserGoal(userId = userId, projectId = projectId, competencyKey = contributionKey)

            val goal = service.findForUser(userId, projectId, setOf(contributionKey, "kotlin"))

            assertEquals(contributionKey, goal?.competencyKey)
        }

        @Test
        fun `reads as no goal once a PM removes the node`() {
            every { userGoalRepository.findByUserIdAndProjectId(userId, projectId) } returns
                UserGoal(userId = userId, projectId = projectId, competencyKey = contributionKey)

            // Graph authoring can remove a contribution node; a stale goal must degrade to "no
            // goal" rather than pointing the path at something that is no longer there.
            val goal = service.findForUser(userId, projectId, setOf("kotlin"))

            assertNull(goal)
        }
    }
}
