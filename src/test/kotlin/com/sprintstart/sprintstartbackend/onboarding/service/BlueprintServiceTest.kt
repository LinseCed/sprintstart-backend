package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintProvenanceSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.GeneratedBlueprint
import com.sprintstart.sprintstartbackend.onboarding.external.model.GeneratedBlueprintStep
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintOrigin
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStep
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toSchema
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintStepRepository
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
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateBlueprintsResponse as AiGenerateBlueprintsResponse

class BlueprintServiceTest {
    private val onboardingAiClient: OnboardingAiClient = mockk()
    private val blueprintRepository: BlueprintRepository = mockk()
    private val blueprintStepRepository: BlueprintStepRepository = mockk()
    private val transactionManager: PlatformTransactionManager = mockk(relaxed = true)
    private val service =
        BlueprintService(onboardingAiClient, blueprintRepository, blueprintStepRepository, transactionManager)

    private fun makeBlueprint(scope: String, version: String, status: BlueprintStatus): Blueprint =
        Blueprint(scope = scope, version = version, status = status)

    private fun makeStep(blueprint: Blueprint, stepId: String, title: String, pos: Int): BlueprintStep =
        BlueprintStep(blueprint = blueprint, stepId = stepId, title = title, position = pos)

    @Nested
    inner class GenerateBlueprints {
        @Test
        fun `maps generated blueprint to PROPOSED and leaves the current ACTIVE untouched`() = runTest {
            val currentActive = makeBlueprint("global", "1", BlueprintStatus.ACTIVE)
            val aiStep = GeneratedBlueprintStep(id = "step-1", title = "Setup")
            val aiBlueprint = GeneratedBlueprint(scope = "global", version = "2", steps = listOf(aiStep))
            val outcome = BlueprintOutcome(scope = "global", status = "updated", blueprint = aiBlueprint)
            coEvery { onboardingAiClient.generateBlueprints(any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(outcome),
            )
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns currentActive
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns
                makeBlueprint("global", "2", BlueprintStatus.PROPOSED)

            val result = service.generateBlueprints(listOf("global"))

            assertEquals(BlueprintStatus.ACTIVE, currentActive.status)
            assertEquals(BlueprintStatus.PROPOSED, savedSlot.captured.status)
            assertEquals(BlueprintOrigin.AI_PROPOSED, savedSlot.captured.origin)
            assertEquals("2", savedSlot.captured.version)
            assertEquals(1, result.outcomes.size)
            assertEquals("global", result.outcomes[0].scope)
            assertEquals("updated", result.outcomes[0].status)
        }

        @Test
        fun `proposes a blueprint when no previous ACTIVE exists`() = runTest {
            val aiStep = GeneratedBlueprintStep(id = "step-1", title = "Setup")
            val aiBlueprint = GeneratedBlueprint(scope = "global", version = "1", steps = listOf(aiStep))
            val outcome = BlueprintOutcome(scope = "global", status = "created", blueprint = aiBlueprint)
            coEvery { onboardingAiClient.generateBlueprints(any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(outcome),
            )
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns
                makeBlueprint("global", "1", BlueprintStatus.PROPOSED)

            val result = service.generateBlueprints(listOf("global"))

            verify(exactly = 1) { blueprintRepository.save(any()) }
            assertEquals(BlueprintStatus.PROPOSED, savedSlot.captured.status)
            assertEquals(1, result.outcomes.size)
            assertEquals("global", result.outcomes[0].scope)
            assertEquals("created", result.outcomes[0].status)
        }

        @Test
        fun `skips outcomes where blueprint is null`() = runTest {
            val outcome = BlueprintOutcome(scope = "global", status = "unchanged", blueprint = null)
            coEvery { onboardingAiClient.generateBlueprints(any(), any()) } returns AiGenerateBlueprintsResponse(
                outcomes = listOf(outcome),
            )
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns null

            service.generateBlueprints(listOf("global"))

            verify(exactly = 0) { blueprintRepository.save(any()) }
        }

        @Test
        fun `does not propose escalated blueprints`() = runTest {
            val aiStep = GeneratedBlueprintStep(id = "step-1", title = "Setup")
            val aiBlueprint = GeneratedBlueprint(scope = "global", version = "2", steps = listOf(aiStep))
            val outcome = BlueprintOutcome(scope = "global", status = "escalated", blueprint = aiBlueprint)
            coEvery { onboardingAiClient.generateBlueprints(any(), any()) } returns AiGenerateBlueprintsResponse(
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
            val activeSlot = slot<List<BlueprintSchema>>()
            val aiBlueprint = GeneratedBlueprint(
                scope = "global",
                version = "2",
                steps = listOf(GeneratedBlueprintStep(id = "step-1", title = "Setup")),
                provenance = BlueprintProvenanceSchema(corpusFingerprint = "new-fp"),
            )
            val outcome = BlueprintOutcome(scope = "global", status = "updated", blueprint = aiBlueprint)
            coEvery { onboardingAiClient.generateBlueprints(any(), capture(activeSlot)) } returns
                AiGenerateBlueprintsResponse(outcomes = listOf(outcome))
            every { blueprintRepository.findByScopeAndStatus("global", BlueprintStatus.ACTIVE) } returns currentActive
            val savedSlot = slot<Blueprint>()
            every { blueprintRepository.save(capture(savedSlot)) } returns currentActive

            service.generateBlueprints(listOf("global"))

            assertEquals("old-fp", activeSlot.captured[0].provenance?.corpusFingerprint)
            assertEquals("new-fp", savedSlot.captured.corpusFingerprint)
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
            archivedBlueprint.steps.add(makeStep(archivedBlueprint, "step-1", "Setup", 0))
            archivedBlueprint.steps.add(makeStep(archivedBlueprint, "step-2", "Configure", 1))
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
        fun `returns proposed blueprints for a scope`() {
            val proposed = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            every { blueprintRepository.findAllByScopeAndStatus("global", BlueprintStatus.PROPOSED) } returns
                listOf(proposed)

            val result = service.listProposed("global")

            assertEquals(1, result.blueprints.size)
            assertEquals("2", result.blueprints[0].version)
        }

        @Test
        fun `returns all proposed blueprints when scope is null`() {
            val proposed = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            every { blueprintRepository.findAllByStatus(BlueprintStatus.PROPOSED) } returns listOf(proposed)

            val result = service.listProposed(null)

            assertEquals(1, result.blueprints.size)
        }
    }

    @Nested
    inner class ApproveStep {
        @Test
        fun `approves a pending step`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val step = makeStep(blueprint, "step-1", "Setup", 0)
            every { blueprintStepRepository.findById(step.id) } returns Optional.of(step)

            val result = service.approveStep(step.id)

            assertEquals(ProposalStatus.APPROVED, step.status)
            assertNotNull(step.decidedAt)
            assertEquals(ProposalStatus.APPROVED, result.status)
        }

        @Test
        fun `throws 404 when the step does not exist`() {
            val id = UUID.randomUUID()
            every { blueprintStepRepository.findById(id) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.approveStep(id) }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }

        @Test
        fun `throws 409 when the step was already decided`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val step = makeStep(blueprint, "step-1", "Setup", 0)
            step.status = ProposalStatus.APPROVED
            every { blueprintStepRepository.findById(step.id) } returns Optional.of(step)

            val ex = assertThrows<ResponseStatusException> { service.approveStep(step.id) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }
    }

    @Nested
    inner class RejectStep {
        @Test
        fun `rejects a pending step and records the reason`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val step = makeStep(blueprint, "step-1", "Setup", 0)
            every { blueprintStepRepository.findById(step.id) } returns Optional.of(step)

            val result = service.rejectStep(step.id, "duplicates an existing step")

            assertEquals(ProposalStatus.REJECTED, step.status)
            assertEquals("duplicates an existing step", step.rejectionReason)
            assertNotNull(step.decidedAt)
            assertEquals(ProposalStatus.REJECTED, result.status)
        }

        @Test
        fun `throws 409 when the step is invariant`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val step = BlueprintStep(
                blueprint = blueprint,
                stepId = "step-1",
                title = "Security review",
                position = 0,
                invariant = true,
            )
            every { blueprintStepRepository.findById(step.id) } returns Optional.of(step)

            val ex = assertThrows<ResponseStatusException> { service.rejectStep(step.id, null) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
            assertEquals(ProposalStatus.PROPOSED, step.status)
        }

        @Test
        fun `throws 404 when the step does not exist`() {
            val id = UUID.randomUUID()
            every { blueprintStepRepository.findById(id) } returns Optional.empty()

            val ex = assertThrows<ResponseStatusException> { service.rejectStep(id, null) }

            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }

        @Test
        fun `throws 409 when the step was already decided`() {
            val blueprint = makeBlueprint("global", "2", BlueprintStatus.PROPOSED)
            val step = makeStep(blueprint, "step-1", "Setup", 0)
            step.status = ProposalStatus.REJECTED
            every { blueprintStepRepository.findById(step.id) } returns Optional.of(step)

            val ex = assertThrows<ResponseStatusException> { service.rejectStep(step.id, null) }

            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }
    }

    @Nested
    inner class ToSchemaFiltering {
        @Test
        fun `excludes rejected steps from the AI-bound schema but keeps them in the API response`() {
            val blueprint = makeBlueprint("global", "1", BlueprintStatus.ACTIVE)
            val keptStep = makeStep(blueprint, "step-1", "Setup", 0)
            val rejectedStep = makeStep(blueprint, "step-2", "Old approach", 1)
            rejectedStep.status = ProposalStatus.REJECTED
            blueprint.steps.add(keptStep)
            blueprint.steps.add(rejectedStep)

            val schema = blueprint.toSchema()
            val response = blueprint.toResponse()

            assertEquals(listOf("step-1"), schema.steps.map { it.id })
            assertEquals(setOf("step-1", "step-2"), response.steps.map { it.id }.toSet())
        }
    }
}
