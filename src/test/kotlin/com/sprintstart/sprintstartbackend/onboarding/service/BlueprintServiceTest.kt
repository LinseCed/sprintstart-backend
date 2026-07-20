package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AiProvenanceSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BaselineCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BaselineSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintOutcome
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintOrigin
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintRequirement
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toSchema
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintCompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateBlueprintsResponse as AiGenerateBlueprintsResponse

class BlueprintServiceTest {
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val blueprintRepository: BlueprintRepository = mockk()
    private val blueprintCompetencyRepository: BlueprintCompetencyRepository = mockk()
    private val competencyRepository: CompetencyRepository = mockk(relaxed = true)
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service =
        BlueprintService(
            onboardingAiClient,
            blueprintRepository,
            blueprintCompetencyRepository,
            competencyRepository,
            transactionManager,
        )

    private fun makeBlueprint(scope: String, version: String, status: BlueprintStatus): Blueprint =
        Blueprint(scope = scope, version = version, status = status)

    private fun makeEntry(
        blueprint: Blueprint,
        key: String,
        pos: Int = 0,
        invariant: Boolean = false,
    ): BlueprintCompetency =
        BlueprintCompetency(blueprint = blueprint, competencyKey = key, position = pos, invariant = invariant)

    private fun makeCompetency(key: String, label: String = key, targetLevel: Int = 2): Competency =
        Competency(key = key, label = label, kind = CompetencyKind.SKILL, targetLevel = targetLevel)

    /** Makes every key the AI proposes exist in the graph, so nothing is dropped as unknown. */
    private fun graphContains(vararg keys: String) {
        every { competencyRepository.findAllByKeyIn(any()) } returns keys.map { makeCompetency(it) }
    }

    @Nested
    inner class GenerateBlueprints {
        @Test
        fun `maps a generated baseline to PROPOSED and leaves the current ACTIVE untouched`() = runTest {
            val currentActive = makeBlueprint("global", "1", BlueprintStatus.ACTIVE)
            val generated = BaselineSchema(
                scope = "global",
                version = "2",
                competencies = listOf(BaselineCompetencySchema(competencyKey = "deploy-runbook")),
            )
            val outcome = BlueprintOutcome(scope = "global", status = "updated", blueprint = generated)
            coEvery { onboardingAiClient.generateBlueprints(any(), any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(outcome),
            )
            graphContains("deploy-runbook")
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns currentActive
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns
                makeBlueprint("global", "2", BlueprintStatus.PROPOSED)

            val result = service.generateBlueprints(listOf("global"))

            assertEquals(BlueprintStatus.ACTIVE, currentActive.status)
            assertEquals(BlueprintStatus.PROPOSED, savedSlot.captured.status)
            assertEquals(BlueprintOrigin.AI_PROPOSED, savedSlot.captured.origin)
            assertEquals("2", savedSlot.captured.version)
            assertEquals(listOf("deploy-runbook"), savedSlot.captured.competencies.map { it.competencyKey })
            assertEquals(1, result.outcomes.size)
            assertEquals("updated", result.outcomes[0].status)
        }

        @Test
        fun `persists the requirement, invariant flag and target-level override of each entry`() = runTest {
            val generated = BaselineSchema(
                scope = "global",
                version = "1",
                competencies = listOf(
                    BaselineCompetencySchema(
                        competencyKey = "security-review",
                        targetLevel = 3,
                        requirement = "required",
                        invariant = true,
                    ),
                ),
            )
            coEvery { onboardingAiClient.generateBlueprints(any(), any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(BlueprintOutcome(scope = "global", status = "created", blueprint = generated)),
            )
            graphContains("security-review")
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns
                makeBlueprint("global", "1", BlueprintStatus.PROPOSED)

            service.generateBlueprints(listOf("global"))

            val entry = savedSlot.captured.competencies.single()
            assertEquals(3, entry.targetLevel)
            assertEquals(BlueprintRequirement.REQUIRED, entry.requirement)
            assertTrue(entry.invariant)
        }

        @Test
        fun `drops proposed entries naming a competency that is not in the graph`() = runTest {
            val generated = BaselineSchema(
                scope = "global",
                version = "1",
                competencies = listOf(
                    BaselineCompetencySchema(competencyKey = "deploy-runbook"),
                    BaselineCompetencySchema(competencyKey = "invented-key"),
                ),
            )
            coEvery { onboardingAiClient.generateBlueprints(any(), any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(BlueprintOutcome(scope = "global", status = "created", blueprint = generated)),
            )
            graphContains("deploy-runbook")
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns
                makeBlueprint("global", "1", BlueprintStatus.PROPOSED)

            service.generateBlueprints(listOf("global"))

            assertEquals(listOf("deploy-runbook"), savedSlot.captured.competencies.map { it.competencyKey })
        }

        @Test
        fun `ignores a target-level override outside the 1 to 4 rank range`() = runTest {
            val generated = BaselineSchema(
                scope = "global",
                version = "1",
                competencies = listOf(BaselineCompetencySchema(competencyKey = "deploy-runbook", targetLevel = 9)),
            )
            coEvery { onboardingAiClient.generateBlueprints(any(), any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(BlueprintOutcome(scope = "global", status = "created", blueprint = generated)),
            )
            graphContains("deploy-runbook")
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns
                makeBlueprint("global", "1", BlueprintStatus.PROPOSED)

            service.generateBlueprints(listOf("global"))

            assertNull(
                savedSlot.captured.competencies
                    .single()
                    .targetLevel,
            )
        }

        @Test
        fun `skips outcomes where the baseline is null`() = runTest {
            val outcome = BlueprintOutcome(scope = "global", status = "unchanged", blueprint = null)
            coEvery { onboardingAiClient.generateBlueprints(any(), any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(outcome),
            )
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null

            service.generateBlueprints(listOf("global"))

            verify(exactly = 0) { blueprintRepository.save(any()) }
        }

        @Test
        fun `does not propose escalated baselines`() = runTest {
            val generated = BaselineSchema(scope = "global", version = "2")
            val outcome = BlueprintOutcome(scope = "global", status = "escalated", blueprint = generated)
            coEvery { onboardingAiClient.generateBlueprints(any(), any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(outcome),
            )
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null

            val result = service.generateBlueprints(listOf("global"))

            verify(exactly = 0) { blueprintRepository.save(any()) }
            assertEquals("escalated", result.outcomes[0].status)
        }

        @Test
        fun `sends the stored corpus fingerprint to the AI and persists the new one`() = runTest {
            val currentActive = Blueprint(
                scope = "global",
                version = "1",
                status = BlueprintStatus.ACTIVE,
                corpusFingerprint = "old-fp",
            )
            val activeSlot = slot<List<BaselineSchema>>()
            val generated = BaselineSchema(
                scope = "global",
                version = "2",
                competencies = listOf(BaselineCompetencySchema(competencyKey = "deploy-runbook")),
                provenance = AiProvenanceSchema(corpusFingerprint = "new-fp"),
            )
            val outcome = BlueprintOutcome(scope = "global", status = "updated", blueprint = generated)
            coEvery { onboardingAiClient.generateBlueprints(any(), capture(activeSlot), any()) } returns
                AiGenerateBlueprintsResponse(outcomes = listOf(outcome))
            graphContains("deploy-runbook")
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns currentActive
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns currentActive

            service.generateBlueprints(listOf("global"))

            assertEquals("old-fp", activeSlot.captured[0].provenance?.corpusFingerprint)
            assertEquals("new-fp", savedSlot.captured.corpusFingerprint)
        }

        @Test
        fun `sends the live competency graph as the set to select from`() = runTest {
            every { competencyRepository.findAll() } returns listOf(makeCompetency("deploy-runbook", "Deploy"))
            val competenciesSlot = slot<List<ActiveCompetencySchema>>()
            coEvery {
                onboardingAiClient.generateBlueprints(any(), any(), capture(competenciesSlot))
            } returns AiGenerateBlueprintsResponse(outcomes = emptyList())
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null

            service.generateBlueprints(listOf("global"))

            assertEquals(listOf("deploy-runbook"), competenciesSlot.captured.map { it.key })
        }
    }

    @Nested
    inner class ListVersions {
        @Test
        fun `returns only ARCHIVED blueprint versions for the scope`() {
            val archived = makeBlueprint("global", "1", BlueprintStatus.ARCHIVED)
            every { blueprintRepository.findAllByScopeAndStatus("global", BlueprintStatus.ARCHIVED) } returns
                listOf(archived)

            val result = service.listVersions("global")

            assertEquals(listOf("1"), result.versions)
            assertEquals("global", result.scope)
        }
    }

    @Nested
    inner class Rollback {
        @Test
        fun `creates a new ACTIVE from the archived version and archives the current ACTIVE`() {
            val archivedBlueprint = makeBlueprint("global", "1", BlueprintStatus.ARCHIVED)
            archivedBlueprint.competencies.add(makeEntry(archivedBlueprint, "deploy-runbook", 0))
            archivedBlueprint.competencies.add(makeEntry(archivedBlueprint, "code-review", 1))
            val currentActive = makeBlueprint("global", "2", BlueprintStatus.ACTIVE)
            every {
                blueprintRepository.findByScopeAndStatusAndVersion("global", BlueprintStatus.ARCHIVED, "1")
            } returns archivedBlueprint
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns currentActive
            every { blueprintRepository.delete(any()) } just Runs
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns
                makeBlueprint("global", "1", BlueprintStatus.ACTIVE)

            service.rollback("global", "1")

            assertEquals(BlueprintStatus.ARCHIVED, currentActive.status)
            assertEquals(BlueprintStatus.ACTIVE, savedSlot.captured.status)
            assertEquals("1", savedSlot.captured.version)
            assertEquals(
                listOf("deploy-runbook", "code-review"),
                savedSlot.captured.competencies.map { it.competencyKey },
            )
            verify(exactly = 1) { blueprintRepository.delete(archivedBlueprint) }
        }

        @Test
        fun `throws 404 when the requested version does not exist`() {
            every {
                blueprintRepository.findByScopeAndStatusAndVersion("global", BlueprintStatus.ARCHIVED, "99")
            } returns null

            val ex = assertThrows<ResponseStatusException> { service.rollback("global", "99") }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }
    }

    @Nested
    inner class Approve {
        @Test
        fun `activates the proposed version and archives the current ACTIVE`() {
            val proposed = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val currentActive = makeBlueprint("global", "1", BlueprintStatus.ACTIVE)
            every {
                blueprintRepository.findByScopeAndStatusAndVersion("global", BlueprintStatus.PROPOSED, "2")
            } returns proposed
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns currentActive

            val result = service.approve("global", "2")

            assertEquals(BlueprintStatus.ACTIVE, proposed.status)
            assertEquals(BlueprintStatus.ARCHIVED, currentActive.status)
            assertEquals("2", result.version)
        }

        @Test
        fun `activates the proposed version when no ACTIVE exists yet`() {
            val proposed = makeBlueprint("global", "1", BlueprintStatus.PROPOSED)
            every {
                blueprintRepository.findByScopeAndStatusAndVersion("global", BlueprintStatus.PROPOSED, "1")
            } returns proposed
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null

            service.approve("global", "1")

            assertEquals(BlueprintStatus.ACTIVE, proposed.status)
        }

        @Test
        fun `throws 404 when no proposed version matches`() {
            every {
                blueprintRepository.findByScopeAndStatusAndVersion("global", BlueprintStatus.PROPOSED, "9")
            } returns null

            val ex = assertThrows<ResponseStatusException> { service.approve("global", "9") }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }
    }

    @Nested
    inner class Reject {
        @Test
        fun `archives the proposed version and leaves the current ACTIVE untouched`() {
            val proposed = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            every {
                blueprintRepository.findByScopeAndStatusAndVersion("global", BlueprintStatus.PROPOSED, "2")
            } returns proposed

            service.reject("global", "2", "not aligned with the current onboarding")

            assertEquals(BlueprintStatus.ARCHIVED, proposed.status)
            verify(exactly = 0) { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) }
        }

        @Test
        fun `throws 404 when no proposed version matches`() {
            every {
                blueprintRepository.findByScopeAndStatusAndVersion("global", BlueprintStatus.PROPOSED, "9")
            } returns null

            val ex = assertThrows<ResponseStatusException> { service.reject("global", "9", null) }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }
    }

    @Nested
    inner class ListProposed {
        @Test
        fun `joins the competency label in from the graph`() {
            val proposed = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            proposed.competencies.add(makeEntry(proposed, "deploy-runbook"))
            every { blueprintRepository.findAllByScopeAndStatus("global", BlueprintStatus.PROPOSED) } returns
                listOf(proposed)
            every { competencyRepository.findAllByKeyIn(setOf("deploy-runbook")) } returns
                listOf(makeCompetency("deploy-runbook", "Deploy the service"))

            val result = service.listProposed("global")

            assertEquals(1, result.blueprints.size)
            assertEquals("2", result.blueprints[0].version)
            assertEquals(
                "Deploy the service",
                result.blueprints[0]
                    .competencies
                    .single()
                    .label,
            )
        }

        @Test
        fun `returns all proposed baselines when scope is null`() {
            val proposed = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            every { blueprintRepository.findAllByStatus(BlueprintStatus.PROPOSED) } returns listOf(proposed)

            val result = service.listProposed(null)

            assertEquals(1, result.blueprints.size)
        }
    }

    @Nested
    inner class ApproveCompetency {
        @Test
        fun `approves a pending entry`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val entry = makeEntry(blueprint, "deploy-runbook")
            every { blueprintCompetencyRepository.findById(entry.id) } returns Optional.of(entry)

            val result = service.approveCompetency(entry.id)

            assertEquals(ProposalStatus.APPROVED, entry.status)
            assertNotNull(entry.decidedAt)
            assertEquals(ProposalStatus.APPROVED, result.status)
        }

        @Test
        fun `throws 404 when the entry does not exist`() {
            val id = UUID.randomUUID()
            every { blueprintCompetencyRepository.findById(id) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.approveCompetency(id) }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }

        @Test
        fun `throws 409 when the entry was already decided`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val entry = makeEntry(blueprint, "deploy-runbook")
            entry.status = ProposalStatus.APPROVED
            every { blueprintCompetencyRepository.findById(entry.id) } returns Optional.of(entry)

            val ex = assertThrows<ResponseStatusException> { service.approveCompetency(entry.id) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }
    }

    @Nested
    inner class RejectCompetency {
        @Test
        fun `rejects a pending entry and records the reason`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val entry = makeEntry(blueprint, "deploy-runbook")
            every { blueprintCompetencyRepository.findById(entry.id) } returns Optional.of(entry)

            val result = service.rejectCompetency(entry.id, "not expected of everyone")

            assertEquals(ProposalStatus.REJECTED, entry.status)
            assertEquals("not expected of everyone", entry.rejectionReason)
            assertNotNull(entry.decidedAt)
            assertEquals(ProposalStatus.REJECTED, result.status)
        }

        @Test
        fun `throws 409 when the entry is invariant`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val entry = makeEntry(blueprint, "security-review", invariant = true)
            every { blueprintCompetencyRepository.findById(entry.id) } returns Optional.of(entry)

            val ex = assertThrows<ResponseStatusException> { service.rejectCompetency(entry.id, null) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
            assertEquals(ProposalStatus.PROPOSED, entry.status)
        }

        @Test
        fun `throws 404 when the entry does not exist`() {
            val id = UUID.randomUUID()
            every { blueprintCompetencyRepository.findById(id) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.rejectCompetency(id, null) }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }

        @Test
        fun `throws 409 when the entry was already decided`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val entry = makeEntry(blueprint, "deploy-runbook")
            entry.status = ProposalStatus.REJECTED
            every { blueprintCompetencyRepository.findById(entry.id) } returns Optional.of(entry)

            val ex = assertThrows<ResponseStatusException> { service.rejectCompetency(entry.id, null) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }
    }

    @Nested
    inner class Mapping {
        @Test
        fun `excludes rejected entries from the AI-bound schema but keeps them in the API response`() {
            val blueprint = makeBlueprint("global", "1", BlueprintStatus.ACTIVE)
            val kept = makeEntry(blueprint, "deploy-runbook", 0)
            val rejected = makeEntry(blueprint, "old-approach", 1)
            rejected.status = ProposalStatus.REJECTED
            blueprint.competencies.add(kept)
            blueprint.competencies.add(rejected)

            val schema = blueprint.toSchema()
            val response = blueprint.toResponse()

            assertEquals(listOf("deploy-runbook"), schema.competencies.map { it.competencyKey })
            assertEquals(
                setOf("deploy-runbook", "old-approach"),
                response.competencies.map { it.competencyKey }.toSet(),
            )
        }

        @Test
        fun `resolves the response target level to the competency bar unless the baseline overrides it`() {
            val blueprint = makeBlueprint("global", "1", BlueprintStatus.ACTIVE)
            val inherited = makeEntry(blueprint, "deploy-runbook", 0)
            val overridden = makeEntry(blueprint, "code-review", 1).apply { targetLevel = 4 }
            blueprint.competencies.add(inherited)
            blueprint.competencies.add(overridden)
            val graph = listOf(
                makeCompetency("deploy-runbook", targetLevel = 2),
                makeCompetency("code-review", targetLevel = 2),
            ).associateBy { it.key }

            val response = blueprint.toResponse(graph)

            val byKey = response.competencies.associateBy { it.competencyKey }
            assertEquals(2, byKey.getValue("deploy-runbook").targetLevel)
            assertEquals(false, byKey.getValue("deploy-runbook").targetLevelOverridden)
            assertEquals(4, byKey.getValue("code-review").targetLevel)
            assertEquals(true, byKey.getValue("code-review").targetLevelOverridden)
        }

        @Test
        fun `shows an entry whose competency no longer exists under its bare key`() {
            val blueprint = makeBlueprint("global", "1", BlueprintStatus.ACTIVE)
            blueprint.competencies.add(makeEntry(blueprint, "removed-node"))

            val response = blueprint.toResponse(emptyMap())

            assertEquals("removed-node", response.competencies.single().label)
        }
    }
}
