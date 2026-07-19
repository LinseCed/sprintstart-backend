package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeClassification
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ChangeType
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStep
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphChange
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphVersion
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphChangeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGraphPinRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class CompetencyPathServiceTest {
    private val competencyRepository: CompetencyRepository = mockk()
    private val competencyEdgeRepository: CompetencyEdgeRepository = mockk()
    private val userCompetencyStateRepository: UserCompetencyStateRepository = mockk()
    private val pathProjectionService: PathProjectionService = PathProjectionService(GraphTraversalService())
    private val competencyGraphVersionService: CompetencyGraphVersionService = mockk()
    private val competencyGraphVersionRepository: CompetencyGraphVersionRepository = mockk()
    private val competencyGraphChangeRepository: CompetencyGraphChangeRepository = mockk()
    private val userGraphPinRepository: UserGraphPinRepository = mockk()
    private val effectiveGraphResolver: EffectiveGraphResolver = EffectiveGraphResolver()
    private val verificationRepository: VerificationRepository = mockk()
    private val blueprintRepository: BlueprintRepository = mockk()
    private val userApi: UserApi = mockk()
    private val service = CompetencyPathService(
        competencyRepository,
        competencyEdgeRepository,
        userCompetencyStateRepository,
        pathProjectionService,
        competencyGraphVersionService,
        competencyGraphVersionRepository,
        competencyGraphChangeRepository,
        userGraphPinRepository,
        effectiveGraphResolver,
        verificationRepository,
        blueprintRepository,
        userApi,
    )

    private val authId = "auth|test-user"
    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    private fun stubNoVerifications() {
        every { verificationRepository.findAllByCompetencyKeyIn(any()) } returns emptyList()
    }

    /**
     * Stubs the blueprint->target bridge to declare no keys, so the path falls back to targeting
     * every visible competency -- the behavior these tests assert.
     */
    private fun stubBridgeFallsBackToAllVisible() {
        every { userApi.getProjectRolesForUser(userId, projectId) } returns emptyList()
        every { blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, any(), any()) } returns null
        every { blueprintRepository.findByProjectIdIsNullAndScopeAndStatus(any(), any()) } returns null
    }

    @Nested
    inner class GetPathForMe {
        @Test
        fun `resolves the user and projects a path from the visible graph and their ledger`() {
            stubNoVerifications()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            stubBridgeFallsBackToAllVisible()
            every { competencyRepository.findAll() } returns listOf(
                Competency(key = "git", label = "Git", kind = CompetencyKind.SKILL),
                Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL),
            )
            every { competencyEdgeRepository.findAll() } returns emptyList()
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns listOf(
                UserCompetencyState(
                    userId = userId,
                    competencyKey = "git",
                    level = 3,
                    source = CompetencySource.ASSESSED,
                ),
            )
            every { competencyGraphVersionService.currentVersion() } returns 7
            every { userGraphPinRepository.findByUserId(userId) } returns
                UserGraphPin(userId = userId, pinnedVersion = 7)
            every { competencyGraphVersionRepository.findAllByVersionGreaterThanOrderByVersionAsc(7) } returns
                emptyList()
            every { competencyGraphChangeRepository.findAll() } returns listOf(
                CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = "git"),
                CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = "kotlin"),
            )

            val result = service.getPathForMe(authId, projectId)

            assertThat(result.nodes.map { it.key }).containsExactlyInAnyOrder("git", "kotlin")
            assertThat(result.graphVersion).isEqualTo(7)
            verify(exactly = 1) { userCompetencyStateRepository.findAllByUserId(userId) }
        }

        @Test
        fun `echoes the pinned version, not the live head, when structural changes are held back`() {
            stubNoVerifications()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            stubBridgeFallsBackToAllVisible()
            every { competencyRepository.findAll() } returns listOf(
                Competency(key = "git", label = "Git", kind = CompetencyKind.SKILL),
            )
            every { competencyEdgeRepository.findAll() } returns emptyList()
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
            every { competencyGraphVersionService.currentVersion() } returns 8
            every { userGraphPinRepository.findByUserId(userId) } returns
                UserGraphPin(userId = userId, pinnedVersion = 7)
            every { competencyGraphVersionRepository.findAllByVersionGreaterThanOrderByVersionAsc(7) } returns
                listOf(CompetencyGraphVersion(version = 8, classification = ChangeClassification.STRUCTURAL))
            every { competencyGraphChangeRepository.findAll() } returns listOf(
                CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = "git"),
            )

            val result = service.getPathForMe(authId, projectId)

            assertThat(result.graphVersion).isEqualTo(7)
        }

        @Test
        fun `lazily creates the graph pin at the current version on first call`() {
            stubNoVerifications()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            stubBridgeFallsBackToAllVisible()
            every { competencyRepository.findAll() } returns emptyList()
            every { competencyEdgeRepository.findAll() } returns emptyList()
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
            every { competencyGraphVersionService.currentVersion() } returns 3
            every { userGraphPinRepository.findByUserId(userId) } returns null
            val savedSlot = slot<UserGraphPin>()
            every { userGraphPinRepository.save(capture(savedSlot)) } answers { savedSlot.captured }
            every { competencyGraphVersionRepository.findAllByVersionGreaterThanOrderByVersionAsc(3) } returns
                emptyList()
            every { competencyGraphChangeRepository.findAll() } returns emptyList()

            service.getPathForMe(authId, projectId)

            assertThat(savedSlot.captured.userId).isEqualTo(userId)
            assertThat(savedSlot.captured.pinnedVersion).isEqualTo(3)
        }

        @Test
        fun `throws 404 when the user cannot be resolved`() {
            every { userApi.getUserIdByAuthId(authId) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.getPathForMe(authId, projectId) }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }

        @Test
        fun `narrows the path to the competency keys the project blueprint declares`() {
            stubNoVerifications()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            every { userApi.getProjectRolesForUser(userId, projectId) } returns emptyList()
            every { competencyRepository.findAll() } returns listOf(
                Competency(key = "git", label = "Git", kind = CompetencyKind.SKILL),
                Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL),
            )
            every { competencyEdgeRepository.findAll() } returns emptyList()
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
            every { competencyGraphVersionService.currentVersion() } returns 1
            every { userGraphPinRepository.findByUserId(userId) } returns
                UserGraphPin(userId = userId, pinnedVersion = 1)
            every { competencyGraphVersionRepository.findAllByVersionGreaterThanOrderByVersionAsc(1) } returns
                emptyList()
            every { competencyGraphChangeRepository.findAll() } returns listOf(
                CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = "git"),
                CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = "kotlin"),
            )
            // The project's global blueprint declares only "git" as a target, so "kotlin" -- though
            // visible -- must not appear on the path.
            val blueprint = Blueprint(
                projectId = projectId,
                scope = "global",
                version = "1",
                status = BlueprintStatus.ACTIVE,
            )
            blueprint.steps.add(
                BlueprintStep(
                    blueprint = blueprint,
                    stepId = "s1",
                    competencyKey = "git",
                    title = "Learn Git",
                    position = 0,
                ),
            )
            every {
                blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "global", BlueprintStatus.ACTIVE)
            } returns blueprint

            val result = service.getPathForMe(authId, projectId)

            assertThat(result.nodes.map { it.key }).containsExactly("git")
        }

        @Test
        fun `annotates a node with the step configured to teach-verify it`() {
            val stepId = UUID.randomUUID()
            every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
            stubBridgeFallsBackToAllVisible()
            every { competencyRepository.findAll() } returns listOf(
                Competency(key = "git", label = "Git", kind = CompetencyKind.SKILL),
                Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL),
            )
            every { competencyEdgeRepository.findAll() } returns emptyList()
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
            every { competencyGraphVersionService.currentVersion() } returns 1
            every { userGraphPinRepository.findByUserId(userId) } returns
                UserGraphPin(userId = userId, pinnedVersion = 1)
            every { competencyGraphVersionRepository.findAllByVersionGreaterThanOrderByVersionAsc(1) } returns
                emptyList()
            every { competencyGraphChangeRepository.findAll() } returns listOf(
                CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = "git"),
                CompetencyGraphChange(version = 1, changeType = ChangeType.NODE_ADDED, competencyKey = "kotlin"),
            )
            every { verificationRepository.findAllByCompetencyKeyIn(any()) } returns listOf(
                Verification(
                    stepId = stepId,
                    type = VerificationType.ATTEST,
                    prompt = "Confirm?",
                    competencyKey = "kotlin",
                    level = "beginner",
                ),
            )

            val result = service.getPathForMe(authId, projectId)

            assertThat(result.nodes.first { it.key == "kotlin" }.stepId).isEqualTo(stepId)
            assertThat(result.nodes.first { it.key == "git" }.stepId).isNull()
            assertThat(result.nodes.first { it.key == "kotlin" }.verificationType)
                .isEqualTo(VerificationType.ATTEST)
            assertThat(result.nodes.first { it.key == "git" }.verificationType).isNull()
        }
    }
}
