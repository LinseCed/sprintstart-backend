package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencySource
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.OnboardingAiPathEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.external.model.PathPhase
import com.sprintstart.sprintstartbackend.onboarding.external.model.PathStep
import com.sprintstart.sprintstartbackend.onboarding.external.model.SkillAssessmentSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPathRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.external.UserOnboardingProfile
import com.sprintstart.sprintstartbackend.user.external.dto.ProjectRoleDto
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

class OnboardingPersonalizationServiceTest {
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val onboardingPathRepository: OnboardingPathRepository = mockk()
    private val blueprintRepository: BlueprintRepository = mockk()
    private val userCompetencyStateRepository: UserCompetencyStateRepository = mockk()
    private val competencyRepository: CompetencyRepository = mockk()
    private val verificationRepository: VerificationRepository = mockk(relaxed = true)
    private val userApi: UserApi = mockk()
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)

    private val service = OnboardingPersonalizationService(
        onboardingAiClient,
        onboardingPathRepository,
        blueprintRepository,
        userCompetencyStateRepository,
        competencyRepository,
        verificationRepository,
        userApi,
        transactionManager,
    )

    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val authId = "auth|test-user"
    private val profile = UserOnboardingProfile(
        id = userId,
        projectRoles = listOf(
            ProjectRoleDto(roleId = UUID.randomUUID(), name = "Backend", description = "Backend work"),
        ),
    )
    private val backendRole = ProjectRoleDto(roleId = UUID.randomUUID(), name = "Backend", description = "Backend work")

    private fun activeBlueprint(scope: String): Blueprint =
        Blueprint(projectId = projectId, scope = scope, version = "1", status = BlueprintStatus.ACTIVE)

    /** Stubs project membership + a single project role so scope resolution succeeds. */
    private fun stubMembershipAndRole() {
        every { userApi.userHasAccessToProject(authId, projectId) } returns true
        every { userApi.getProjectRolesForUser(userId, projectId) } returns listOf(backendRole)
    }

    /** Stubs an ACTIVE project baseline for both required scopes so personalization can stream. */
    private fun stubBaselinesPresent() {
        every {
            blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "global", BlueprintStatus.ACTIVE)
        } returns activeBlueprint("global")
        every {
            blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "area:backend", BlueprintStatus.ACTIVE)
        } returns activeBlueprint("area:backend")
        every { onboardingPathRepository.deleteByUserIdAndProjectId(userId, projectId) } just runs
        stubEmptyLedger()
    }

    private fun stubEmptyLedger() {
        every { userCompetencyStateRepository.findAllByUserId(userId) } returns emptyList()
    }

    @Nested
    inner class Personalize {
        @Test
        fun `throws 404 when user profile not found`() {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.personalize(authId, projectId) }

            assertEquals(404, ex.statusCode.value())
        }

        @Test
        fun `throws 403 when user is not a member of the project`() {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            every { userApi.userHasAccessToProject(authId, projectId) } returns false

            val ex = assertThrows<ResponseStatusException> { service.personalize(authId, projectId) }

            assertEquals(403, ex.statusCode.value())
        }

        @Test
        fun `throws 400 when user has no role in the project`() {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            every { userApi.userHasAccessToProject(authId, projectId) } returns true
            every { userApi.getProjectRolesForUser(userId, projectId) } returns emptyList()

            val ex = assertThrows<ResponseStatusException> { service.personalize(authId, projectId) }

            assertEquals(400, ex.statusCode.value())
        }

        @Test
        fun `reads active project blueprints for the global and area scopes`() = runTest {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every { onboardingAiClient.generatePath(any(), any(), any()) } returns flowOf(
                OnboardingAiPathEvent(type = "done"),
            )

            service.personalize(authId, projectId).toList()

            verify(exactly = 1) {
                blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "global", BlueprintStatus.ACTIVE)
            }
            verify(exactly = 1) {
                blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "area:backend", BlueprintStatus.ACTIVE)
            }
        }

        @Test
        fun `falls back to the unscoped blueprint when the project has none of its own`() = runTest {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubEmptyLedger()
            every { onboardingPathRepository.deleteByUserIdAndProjectId(userId, projectId) } just runs
            // Project has no blueprint of its own for either scope -> fall back to the null-project one.
            every {
                blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, any(), BlueprintStatus.ACTIVE)
            } returns null
            every {
                blueprintRepository.findByProjectIdIsNullAndScopeAndStatus("global", BlueprintStatus.ACTIVE)
            } returns activeBlueprint("global")
            every {
                blueprintRepository.findByProjectIdIsNullAndScopeAndStatus("area:backend", BlueprintStatus.ACTIVE)
            } returns activeBlueprint("area:backend")
            every { onboardingAiClient.generatePath(any(), any(), any()) } returns flowOf(
                OnboardingAiPathEvent(type = "done"),
            )

            val events = service.personalize(authId, projectId).toList()

            assertEquals("done", events.last().type)
            verify(exactly = 1) {
                blueprintRepository.findByProjectIdIsNullAndScopeAndStatus("area:backend", BlueprintStatus.ACTIVE)
            }
        }

        @Test
        fun `passes active blueprints to AI client`() = runTest {
            val blueprintSlot = slot<List<BlueprintSchema>>()
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every {
                onboardingAiClient.generatePath(any(), any(), capture(blueprintSlot))
            } returns flowOf(OnboardingAiPathEvent(type = "done"))

            service.personalize(authId, projectId).toList()

            assertEquals(2, blueprintSlot.captured.size)
            assertEquals(setOf("global", "area:backend"), blueprintSlot.captured.map { it.scope }.toSet())
        }

        @Test
        fun `maps the competency ledger to leveled skills for the AI client, skipping level-0 rows`() = runTest {
            val skillsSlot = slot<List<SkillAssessmentSchema>>()
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every { userCompetencyStateRepository.findAllByUserId(userId) } returns listOf(
                UserCompetencyState(
                    userId = userId,
                    competencyKey = "kotlin",
                    level = 3,
                    source = CompetencySource.VERIFIED,
                ),
                UserCompetencyState(
                    userId = userId,
                    competencyKey = "git",
                    level = 0,
                    source = CompetencySource.ASSESSED,
                ),
            )
            every { competencyRepository.findAllByKeyIn(listOf("kotlin")) } returns
                listOf(Competency(key = "kotlin", label = "Kotlin", kind = CompetencyKind.SKILL))
            every {
                onboardingAiClient.generatePath(any(), capture(skillsSlot), any())
            } returns flowOf(OnboardingAiPathEvent(type = "done"))

            service.personalize(authId, projectId).toList()

            assertEquals(listOf(SkillAssessmentSchema(name = "Kotlin", level = "advanced")), skillsSlot.captured)
        }

        @Test
        fun `emits an error and does not generate a path when a required baseline is missing`() = runTest {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubEmptyLedger()
            every {
                blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "global", BlueprintStatus.ACTIVE)
            } returns activeBlueprint("global")
            every {
                blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "area:backend", BlueprintStatus.ACTIVE)
            } returns null
            every {
                blueprintRepository.findByProjectIdIsNullAndScopeAndStatus("area:backend", BlueprintStatus.ACTIVE)
            } returns null

            val events = service.personalize(authId, projectId).toList()

            assertEquals(1, events.size)
            assertEquals("error", events[0].type)
            assertEquals("Onboarding baseline for area:backend is not ready yet", events[0].message)
            verify(exactly = 0) { onboardingAiClient.generatePath(any(), any(), any()) }
            verify(exactly = 0) { onboardingPathRepository.deleteByUserIdAndProjectId(any(), any()) }
        }

        @Test
        fun `maps stage events from AI client`() = runTest {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every { onboardingAiClient.generatePath(any(), any(), any()) } returns flowOf(
                OnboardingAiPathEvent(type = "stage", name = "retrieve", detail = "Retrieving documents"),
                OnboardingAiPathEvent(type = "done"),
            )

            val events = service.personalize(authId, projectId).toList()

            assertEquals(2, events.size)
            assertEquals("stage", events[0].type)
            assertEquals("retrieve", events[0].name)
            assertEquals("Retrieving documents", events[0].detail)
            assertEquals("done", events[1].type)
        }

        @Test
        fun `maps error events from AI client`() = runTest {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every { onboardingAiClient.generatePath(any(), any(), any()) } returns flowOf(
                OnboardingAiPathEvent(type = "error", message = "LLM unavailable"),
            )

            val events = service.personalize(authId, projectId).toList()

            assertEquals(1, events.size)
            assertEquals("error", events[0].type)
            assertEquals("LLM unavailable", events[0].message)
        }

        @Test
        fun `maps path event and persists the generated path scoped to the project`() = runTest {
            val path = OnboardingPath(workingArea = "backend")
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every { onboardingAiClient.generatePath(any(), any(), any()) } returns flowOf(
                OnboardingAiPathEvent(type = "path", path = path),
            )
            val savedSlot = slot<com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath>()
            every { onboardingPathRepository.save(capture(savedSlot)) } answers { firstArg() }

            val events = service.personalize(authId, projectId).toList()

            assertEquals(1, events.size)
            assertEquals("path", events[0].type)
            assertEquals(userId, events[0].path?.userId)
            assertEquals(projectId, savedSlot.captured.projectId)
            verify(exactly = 1) { onboardingPathRepository.save(any()) }
        }

        @Test
        fun `gives every competency-tagged step a check, so the graph gets openable modules`() = runTest {
            val path = OnboardingPath(
                workingArea = "backend",
                phases = listOf(
                    PathPhase(
                        title = "Phase 1",
                        steps = listOf(
                            PathStep(
                                title = "Run the stack locally",
                                description = "Boot it end to end",
                                competencyKey = "local-dev",
                            ),
                            PathStep(title = "Read the notes", description = "Context only"),
                        ),
                    ),
                ),
            )
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every { onboardingAiClient.generatePath(any(), any(), any()) } returns flowOf(
                OnboardingAiPathEvent(type = "path", path = path),
            )
            every { onboardingPathRepository.save(any()) } answers { firstArg() }
            val saved = slot<List<Verification>>()
            every { verificationRepository.saveAll(capture(saved)) } answers { firstArg() }

            service.personalize(authId, projectId).toList()

            // Without this, a generated path had a full graph and zero openable nodes: a node only
            // becomes a module when a Verification carries its competency key.
            assertEquals(1, saved.captured.size)
            assertEquals("local-dev", saved.captured[0].competencyKey)
            assertEquals(VerificationType.KNOWLEDGE, saved.captured[0].type)
        }

        @Test
        fun `does not invent a check for a step with no competency`() = runTest {
            val path = OnboardingPath(
                workingArea = "backend",
                phases = listOf(
                    PathPhase(
                        title = "Phase 1",
                        steps = listOf(PathStep(title = "Read the notes", description = "Context")),
                    ),
                ),
            )
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every { onboardingAiClient.generatePath(any(), any(), any()) } returns flowOf(
                OnboardingAiPathEvent(type = "path", path = path),
            )
            every { onboardingPathRepository.save(any()) } answers { firstArg() }

            service.personalize(authId, projectId).toList()

            // There would be no graph node to open it from.
            verify(exactly = 0) { verificationRepository.saveAll(any<List<Verification>>()) }
        }

        @Test
        fun `deletes existing path for the project before generating new one`() = runTest {
            every { userApi.getOnboardingProfileByAuthId(authId) } returns Optional.of(profile)
            stubMembershipAndRole()
            stubBaselinesPresent()
            every { onboardingAiClient.generatePath(any(), any(), any()) } returns flowOf(
                OnboardingAiPathEvent(type = "done"),
            )

            service.personalize(authId, projectId).toList()

            verify(exactly = 1) { onboardingPathRepository.deleteByUserIdAndProjectId(userId, projectId) }
        }
    }
}
