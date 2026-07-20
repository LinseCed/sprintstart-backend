package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContentProvenance
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModulePageKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModuleStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.VerificationType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyModule
import com.sprintstart.sprintstartbackend.onboarding.model.entity.ModulePage
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Verification
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.CreateCompetencyModuleRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.CreateModulePageRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.ReorderModulePagesRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.module.UpdateModulePageRequest
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyModuleRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.ModulePageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class CompetencyModuleServiceTest {
    private val competencyModuleRepository: CompetencyModuleRepository = mockk(relaxed = true)
    private val modulePageRepository: ModulePageRepository = mockk()
    private val competencyRepository: CompetencyRepository = mockk()
    private val verificationRepository: VerificationRepository = mockk(relaxed = true)
    private val userApi: UserApi = mockk()
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service = CompetencyModuleService(
        competencyModuleRepository,
        modulePageRepository,
        competencyRepository,
        verificationRepository,
        userApi,
        onboardingAiClient,
        transactionManager,
    )

    private val projectId = UUID.randomUUID()
    private val key = "deploy-runbook"

    private fun competency() = Competency(key = key, label = "Deploy the service", kind = CompetencyKind.SKILL)

    private fun module(
        status: ModuleStatus = ModuleStatus.DRAFT,
        version: Int = 1,
    ): CompetencyModule = CompetencyModule(
        competencyKey = key,
        projectId = projectId,
        version = version,
        status = status,
        title = "Deploying",
    )

    private fun page(
        module: CompetencyModule,
        title: String,
        position: Int,
        provenance: ContentProvenance = ContentProvenance.AI,
    ): ModulePage = ModulePage(
        module = module,
        kind = ModulePageKind.LESSON,
        title = title,
        body = title,
        position = position,
        provenance = provenance,
    ).also { module.pages.add(it) }

    private fun stubKnownCompetency() {
        every { competencyRepository.findByKey(key) } returns competency()
    }

    private fun stubNoActive() {
        every {
            competencyModuleRepository.findByCompetencyKeyAndProjectIdAndStatus(key, projectId, ModuleStatus.ACTIVE)
        } returns null
    }

    private fun stubFound(module: CompetencyModule) {
        every { competencyModuleRepository.findById(module.id) } returns Optional.of(module)
    }

    @Nested
    inner class Create {
        @Test
        fun `refuses a module for a competency that is not in the graph`() {
            every { competencyRepository.findByKey("ghost") } returns null

            val ex = assertThrows<ResponseStatusException> {
                service.create(
                    CreateCompetencyModuleRequest(competencyKey = "ghost", projectId = projectId, title = "x"),
                )
            }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }

        @Test
        fun `numbers the new version after the highest existing one`() {
            stubKnownCompetency()
            every {
                competencyModuleRepository.findAllByCompetencyKeyAndProjectIdOrderByVersionDesc(key, projectId)
            } returns listOf(module(ModuleStatus.ACTIVE, version = 3))
            every { verificationRepository.findByModuleId(any()) } returns null
            val saved = slot<CompetencyModule>()
            every { competencyModuleRepository.save(capture(saved)) } answers { saved.captured }

            val result = service.create(
                CreateCompetencyModuleRequest(competencyKey = key, projectId = projectId, title = "Deploying"),
            )

            assertThat(result.version).isEqualTo(4)
            assertThat(result.status).isEqualTo(ModuleStatus.DRAFT)
        }

        @Test
        fun `copies the live version's pages and check so editing starts from what is live`() {
            stubKnownCompetency()
            val active = module(ModuleStatus.ACTIVE)
            page(active, "Why it matters", 0, ContentProvenance.PM)
            page(active, "How to deploy", 1, ContentProvenance.AI)
            every {
                competencyModuleRepository.findAllByCompetencyKeyAndProjectIdOrderByVersionDesc(key, projectId)
            } returns listOf(active)
            every {
                competencyModuleRepository.findByCompetencyKeyAndProjectIdAndStatus(key, projectId, ModuleStatus.ACTIVE)
            } returns active
            every { verificationRepository.findByModuleId(active.id) } returns Verification(
                moduleId = active.id,
                type = VerificationType.KNOWLEDGE,
                prompt = "Explain the rollback path",
                rubric = "Mentions the runbook",
                competencyKey = key,
                level = "intermediate",
            )
            every { verificationRepository.findByModuleId(neq(active.id)) } returns null
            val saved = slot<CompetencyModule>()
            every { competencyModuleRepository.save(capture(saved)) } answers { saved.captured }
            val savedCheck = slot<Verification>()
            every { verificationRepository.save(capture(savedCheck)) } answers { savedCheck.captured }

            val result = service.create(
                CreateCompetencyModuleRequest(
                    competencyKey = key,
                    projectId = projectId,
                    title = "Deploying",
                    copyFromActive = true,
                ),
            )

            assertThat(result.pages.map { it.title }).containsExactly("Why it matters", "How to deploy")
            // Provenance carries over, which is what lets re-synthesis keep leaving a PM page alone.
            assertThat(result.pages.first().provenance).isEqualTo(ContentProvenance.PM)
            assertThat(savedCheck.captured.moduleId).isEqualTo(saved.captured.id)
            assertThat(savedCheck.captured.rubric).isEqualTo("Mentions the runbook")
        }
    }

    @Nested
    inner class Approve {
        @Test
        fun `publishes the version and archives the one it replaces`() {
            val previous = module(ModuleStatus.ACTIVE, version = 1)
            val candidate = module(ModuleStatus.PROPOSED, version = 2)
            page(candidate, "Lesson", 0)
            stubFound(candidate)
            every {
                competencyModuleRepository.findByCompetencyKeyAndProjectIdAndStatus(key, projectId, ModuleStatus.ACTIVE)
            } returns previous
            stubKnownCompetency()
            every { verificationRepository.findByModuleId(any()) } returns null

            val result = service.approve(candidate.id)

            assertThat(result.status).isEqualTo(ModuleStatus.ACTIVE)
            assertThat(previous.status).isEqualTo(ModuleStatus.ARCHIVED)
        }

        @Test
        fun `refuses to publish a module with no pages`() {
            val candidate = module(ModuleStatus.PROPOSED)
            stubFound(candidate)

            val ex = assertThrows<ResponseStatusException> { service.approve(candidate.id) }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(candidate.status).isEqualTo(ModuleStatus.PROPOSED)
        }

        @Test
        fun `refuses to publish an archived version`() {
            val archived = module(ModuleStatus.ARCHIVED)
            page(archived, "Lesson", 0)
            stubFound(archived)

            val ex = assertThrows<ResponseStatusException> { service.approve(archived.id) }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.CONFLICT)
        }
    }

    @Nested
    inner class Reject {
        @Test
        fun `refuses to reject the live module, since that would leave the node with nothing`() {
            val live = module(ModuleStatus.ACTIVE)
            stubFound(live)

            val ex = assertThrows<ResponseStatusException> { service.reject(live.id, null) }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(live.status).isEqualTo(ModuleStatus.ACTIVE)
        }
    }

    @Nested
    inner class Pages {
        @Test
        fun `appends a page and marks it PM-authored`() {
            val draft = module()
            page(draft, "Existing", 0)
            stubFound(draft)

            val result = service.addPage(
                draft.id,
                CreateModulePageRequest(kind = ModulePageKind.TASK, title = "Try it"),
            )

            assertThat(result.position).isEqualTo(1)
            assertThat(result.provenance).isEqualTo(ContentProvenance.PM)
        }

        @Test
        fun `inserting at a position renumbers the pages densely`() {
            val draft = module()
            page(draft, "First", 0)
            page(draft, "Second", 1)
            stubFound(draft)

            service.addPage(
                draft.id,
                CreateModulePageRequest(kind = ModulePageKind.CONTEXT, title = "Why", position = 0),
            )

            assertThat(draft.pages.map { it.title }).containsExactly("Why", "First", "Second")
            assertThat(draft.pages.map { it.position }).containsExactly(0, 1, 2)
        }

        @Test
        fun `editing an AI page makes it PM-authored, so re-synthesis leaves it alone`() {
            val draft = module()
            val existing = page(draft, "Lesson", 0, ContentProvenance.AI)
            every { modulePageRepository.findById(existing.id) } returns Optional.of(existing)

            val result = service.updatePage(existing.id, UpdateModulePageRequest(body = "Rewritten by a human"))

            assertThat(result.provenance).isEqualTo(ContentProvenance.PM)
            assertThat(existing.body).isEqualTo("Rewritten by a human")
        }

        @Test
        fun `a live module is not edited in place`() {
            val live = module(ModuleStatus.ACTIVE)
            val existing = page(live, "Lesson", 0)
            every { modulePageRepository.findById(existing.id) } returns Optional.of(existing)

            val ex = assertThrows<ResponseStatusException> {
                service.updatePage(existing.id, UpdateModulePageRequest(title = "Edited"))
            }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.CONFLICT)
            assertThat(existing.title).isEqualTo("Lesson")
        }

        @Test
        fun `reorders in one call`() {
            val draft = module()
            val first = page(draft, "First", 0)
            val second = page(draft, "Second", 1)
            val third = page(draft, "Third", 2)
            stubFound(draft)
            stubKnownCompetency()
            every { verificationRepository.findByModuleId(any()) } returns null

            val result = service.reorderPages(
                draft.id,
                ReorderModulePagesRequest(pageIds = listOf(third.id, first.id, second.id)),
            )

            assertThat(result.pages.map { it.title }).containsExactly("Third", "First", "Second")
        }

        @Test
        fun `rejects a partial reorder rather than quietly losing a page`() {
            val draft = module()
            val first = page(draft, "First", 0)
            page(draft, "Second", 1)
            stubFound(draft)

            val ex = assertThrows<ResponseStatusException> {
                service.reorderPages(draft.id, ReorderModulePagesRequest(pageIds = listOf(first.id)))
            }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(draft.pages).hasSize(2)
        }
    }

    @Nested
    inner class GetForMe {
        @Test
        fun `serves a live module to a member of its project`() {
            val live = module(ModuleStatus.ACTIVE)
            page(live, "Lesson", 0)
            stubFound(live)
            stubKnownCompetency()
            every { verificationRepository.findByModuleId(live.id) } returns null
            every { userApi.userHasAccessToProject("auth|hire", projectId) } returns true

            val result = service.getForMe("auth|hire", live.id)

            assertThat(result.pages.map { it.title }).containsExactly("Lesson")
        }

        @Test
        fun `serves the same module to a second hire -- there is no per-user copy`() {
            val live = module(ModuleStatus.ACTIVE)
            page(live, "Lesson", 0)
            stubFound(live)
            stubKnownCompetency()
            every { verificationRepository.findByModuleId(live.id) } returns null
            every { userApi.userHasAccessToProject(any(), projectId) } returns true

            val first = service.getForMe("auth|hire-a", live.id)
            val second = service.getForMe("auth|hire-b", live.id)

            assertThat(first.id).isEqualTo(second.id)
            assertThat(first.pages.map { it.id }).isEqualTo(second.pages.map { it.id })
        }

        @Test
        fun `reports an unpublished draft as absent, not forbidden`() {
            val draft = module()
            stubFound(draft)

            val ex = assertThrows<ResponseStatusException> { service.getForMe("auth|hire", draft.id) }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }

        @Test
        fun `refuses a member of another project`() {
            val live = module(ModuleStatus.ACTIVE)
            stubFound(live)
            every { userApi.userHasAccessToProject("auth|outsider", projectId) } returns false

            val ex = assertThrows<ResponseStatusException> { service.getForMe("auth|outsider", live.id) }

            assertThat(ex.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        }
    }

    init {
        stubNoActive()
    }
}
