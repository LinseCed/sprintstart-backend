package com.sprintstart.sprintstartbackend.onboarding.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepType
import com.sprintstart.sprintstartbackend.onboarding.model.request.phase.CreateOnboardingPhaseRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.phase.UpdateOnboardingPhaseRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.resource.CreateOnboardingResourceRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.resource.UpdateOnboardingResourceRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.step.CreateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.step.UpdateOnboardingStepRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.task.CreateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.task.UpdateOnboardingTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.CreateOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.GetOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.GetOnboardingPhasesResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.phase.UpdateOnboardingPhaseResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.resource.CreateOnboardingResourceResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.resource.GetOnboardingResourceResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.resource.GetOnboardingResourcesResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.resource.UpdateOnboardingResourceResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.CreateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.GetOnboardingStepsResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.step.UpdateOnboardingStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.CreateOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.GetOnboardingTasksResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.task.UpdateOnboardingTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.service.OnboardingService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@WebMvcTest(OnboardingController::class)
class OnboardingControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private val objectMapper = jacksonObjectMapper()

    @MockkBean
    private lateinit var onboardingService: OnboardingService

    private val pathId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val userId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val phaseId: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val stepId: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val taskId: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val resourceId: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
    private val createdAt: Instant = Instant.parse("2026-06-01T10:00:00Z")

    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------

    @Test
    fun `getAllPaths should return 200 and all paths`() {
        val response = listOf(
            GetOnboardingPathsResponse(
                id = pathId,
                userId = userId,
                createdAt = createdAt,
                phaseCount = 2,
                stepCount = 5,
                finishedStepCount = 1,
            ),
        )

        every { onboardingService.getAllOnboardingPaths() } returns response

        mockMvc.get("/api/v1/onboarding/paths")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(pathId.toString()) }
                jsonPath("$[0].userId") { value(userId.toString()) }
                jsonPath("$[0].phaseCount") { value(2) }
                jsonPath("$[0].stepCount") { value(5) }
                jsonPath("$[0].finishedStepCount") { value(1) }
            }

        verify(exactly = 1) { onboardingService.getAllOnboardingPaths() }
    }

    @Test
    fun `getPath should return 200 and path`() {
        val response = GetOnboardingPathResponse(
            id = pathId,
            userId = userId,
            createdAt = createdAt,
            phases = emptyList(),
        )

        every { onboardingService.getOnboardingPath(pathId) } returns response

        mockMvc.get("/api/v1/onboarding/paths/$pathId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(pathId.toString()) }
                jsonPath("$.userId") { value(userId.toString()) }
                jsonPath("$.phases.length()") { value(0) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingPath(pathId) }
    }

    @Test
    fun `getPath should return 404 if path not found`() {
        every {
            onboardingService.getOnboardingPath(pathId)
        } throws ResponseStatusException(HttpStatus.NOT_FOUND, "No onboarding path found")

        mockMvc.get("/api/v1/onboarding/paths/$pathId")
            .andExpect {
                status { isNotFound() }
            }

        verify(exactly = 1) { onboardingService.getOnboardingPath(pathId) }
    }

    @Test
    fun `getPathForUser should return 200 and path for user`() {
        val response = GetOnboardingPathForUserResponse(
            id = pathId,
            userId = userId,
            createdAt = createdAt,
            phases = emptyList(),
        )

        every { onboardingService.getOnboardingPathByUserId(userId) } returns response

        mockMvc.get("/api/v1/onboarding/$userId/path")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(pathId.toString()) }
                jsonPath("$.userId") { value(userId.toString()) }
                jsonPath("$.phases.length()") { value(0) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingPathByUserId(userId) }
    }

    @Test
    fun `deletePath should return 200`() {
        every { onboardingService.deleteOnboardingPathById(pathId) } just Runs

        mockMvc.delete("/api/v1/onboarding/paths/$pathId")
            .andExpect {
                status { isNoContent() }
            }

        verify(exactly = 1) { onboardingService.deleteOnboardingPathById(pathId) }
    }

    @Test
    fun `deletePathByUserId should return 200`() {
        every { onboardingService.deleteOnboardingPathByUserId(userId) } just Runs

        mockMvc.delete("/api/v1/onboarding/$userId/path")
            .andExpect {
                status { isNoContent() }
            }

        verify(exactly = 1) { onboardingService.deleteOnboardingPathByUserId(userId) }
    }

    // -------------------------------------------------------------------------
    // Phases
    // -------------------------------------------------------------------------

    @Test
    fun `createOnboardingPhase should return 200 and created phase`() {
        val request = CreateOnboardingPhaseRequest(
            position = 1,
            title = "Setup",
            description = "Setup phase",
        )
        val response = CreateOnboardingPhaseResponse(
            id = phaseId,
            pathId = pathId,
            position = 1,
            title = "Setup",
            description = "Setup phase",
        )

        every { onboardingService.createOnboardingPhaseForPathId(pathId, request) } returns response

        mockMvc.post("/api/v1/onboarding/paths/$pathId/phases") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(phaseId.toString()) }
            jsonPath("$.pathId") { value(pathId.toString()) }
            jsonPath("$.position") { value(1) }
            jsonPath("$.title") { value("Setup") }
            jsonPath("$.description") { value("Setup phase") }
        }

        verify(exactly = 1) { onboardingService.createOnboardingPhaseForPathId(pathId, request) }
    }

    @Test
    fun `getOnboardingPhases should return 200 and all phases`() {
        val response = listOf(
            GetOnboardingPhasesResponse(
                id = phaseId,
                pathId = pathId,
                position = 1,
                title = "Setup",
                description = "Setup phase",
            ),
        )

        every { onboardingService.getOnboardingPhases() } returns response

        mockMvc.get("/api/v1/onboarding/phases")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(phaseId.toString()) }
                jsonPath("$[0].pathId") { value(pathId.toString()) }
                jsonPath("$[0].position") { value(1) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingPhases() }
    }

    @Test
    fun `getAllOnboardingPhasesByPathId should return 200 and phases for path`() {
        val response = listOf(
            GetOnboardingPhaseResponse(
                id = phaseId,
                pathId = pathId,
                position = 1,
                title = "Setup",
                description = "Setup phase",
                steps = emptyList(),
            ),
        )

        every { onboardingService.getOnboardingPhasesByPathId(pathId) } returns response

        mockMvc.get("/api/v1/onboarding/paths/$pathId/phases")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(phaseId.toString()) }
                jsonPath("$[0].steps.length()") { value(0) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingPhasesByPathId(pathId) }
    }

    @Test
    fun `getOnboardingPhase should return 200 and phase`() {
        val response = GetOnboardingPhaseResponse(
            id = phaseId,
            pathId = pathId,
            position = 1,
            title = "Setup",
            description = "Setup phase",
            steps = emptyList(),
        )

        every { onboardingService.getOnboardingPhase(phaseId) } returns response

        mockMvc.get("/api/v1/onboarding/phases/$phaseId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(phaseId.toString()) }
                jsonPath("$.pathId") { value(pathId.toString()) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingPhase(phaseId) }
    }

    @Test
    fun `updateOnboardingPhase should return 200 and updated phase`() {
        val request = UpdateOnboardingPhaseRequest(
            position = 2,
            title = "Updated setup",
            description = "Updated setup phase",
        )
        val response = UpdateOnboardingPhaseResponse(
            id = phaseId,
            pathId = pathId,
            position = 2,
            title = "Updated setup",
            description = "Updated setup phase",
        )

        every { onboardingService.updateOnboardingPhase(phaseId, request) } returns response

        mockMvc.put("/api/v1/onboarding/phases/$phaseId") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(phaseId.toString()) }
            jsonPath("$.position") { value(2) }
            jsonPath("$.title") { value("Updated setup") }
        }

        verify(exactly = 1) { onboardingService.updateOnboardingPhase(phaseId, request) }
    }

    @Test
    fun `deleteOnboardingPhaseForPathId should return 200`() {
        every { onboardingService.deleteOnboardingPhase(phaseId) } just Runs

        mockMvc.delete("/api/v1/onboarding/phases/$phaseId")
            .andExpect {
                status { isNoContent() }
            }

        verify(exactly = 1) { onboardingService.deleteOnboardingPhase(phaseId) }
    }

    // -------------------------------------------------------------------------
    // Steps
    // -------------------------------------------------------------------------

    @Test
    fun `createOnboardingStep should return 200 and created step`() {
        val request = CreateOnboardingStepRequest(
            position = 1,
            title = "Read docs",
            description = "Read the internal docs",
            type = StepType.DOCUMENT,
            estimatedMinutes = 30,
            expectedOutcome = "Understands the basics",
        )
        val response = CreateOnboardingStepResponse(
            id = stepId,
            phaseId = phaseId,
            position = 1,
            title = "Read docs",
            description = "Read the internal docs",
            estimatedMinutes = 30,
            expectedOutcome = "Understands the basics",
            status = StepStatus.WAITING,
        )

        every { onboardingService.createOnboardingStepForPhaseId(phaseId, request) } returns response

        mockMvc.post("/api/v1/onboarding/phases/$phaseId/steps") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(stepId.toString()) }
            jsonPath("$.phaseId") { value(phaseId.toString()) }
            jsonPath("$.status") { value("WAITING") }
        }

        verify(exactly = 1) { onboardingService.createOnboardingStepForPhaseId(phaseId, request) }
    }

    @Test
    fun `getOnboardingSteps should return 200 and all steps`() {
        val response = listOf(
            GetOnboardingStepsResponse(
                id = stepId,
                phaseId = phaseId,
                position = 1,
                title = "Read docs",
                description = "Read the internal docs",
                estimatedMinutes = 30,
                status = StepStatus.WAITING,
                completedAt = null,
                skipReason = null,
            ),
        )

        every { onboardingService.getOnboardingSteps() } returns response

        mockMvc.get("/api/v1/onboarding/steps")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(stepId.toString()) }
                jsonPath("$[0].phaseId") { value(phaseId.toString()) }
                jsonPath("$[0].status") { value("WAITING") }
            }

        verify(exactly = 1) { onboardingService.getOnboardingSteps() }
    }

    @Test
    fun `getOnboardingStepsForPhaseId should return 200 and steps for phase`() {
        val response = listOf(
            GetOnboardingStepResponse(
                id = stepId,
                phaseId = phaseId,
                position = 1,
                title = "Read docs",
                description = "Read the internal docs",
                estimatedMinutes = 30,
                tasks = emptyList(),
                resources = emptyList(),
                status = StepStatus.WAITING,
                completedAt = null,
                skipReason = null,
            ),
        )

        every { onboardingService.getOnboardingStepsByPhaseId(phaseId) } returns response

        mockMvc.get("/api/v1/onboarding/phases/$phaseId/steps")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(stepId.toString()) }
                jsonPath("$[0].tasks.length()") { value(0) }
                jsonPath("$[0].resources.length()") { value(0) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingStepsByPhaseId(phaseId) }
    }

    @Test
    fun `getOnboardingStep should return 200 and step`() {
        val response = GetOnboardingStepResponse(
            id = stepId,
            phaseId = phaseId,
            position = 1,
            title = "Read docs",
            description = "Read the internal docs",
            estimatedMinutes = 30,
            tasks = emptyList(),
            resources = emptyList(),
            status = StepStatus.WAITING,
            completedAt = null,
            skipReason = null,
        )

        every { onboardingService.getOnboardingStep(stepId) } returns response

        mockMvc.get("/api/v1/onboarding/steps/$stepId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(stepId.toString()) }
                jsonPath("$.phaseId") { value(phaseId.toString()) }
                jsonPath("$.status") { value("WAITING") }
            }

        verify(exactly = 1) { onboardingService.getOnboardingStep(stepId) }
    }

    @Test
    fun `updateOnboardingStep should return 200 and updated step`() {
        val request = UpdateOnboardingStepRequest(
            position = 2,
            title = "Updated docs",
            description = "Updated docs description",
            type = StepType.DOCUMENT,
            estimatedMinutes = 45,
            expectedOutcome = "Understands updated docs",
            status = StepStatus.FINISHED,
            skipReason = null,
        )
        val response = UpdateOnboardingStepResponse(
            id = stepId,
            phaseId = phaseId,
            position = 2,
            title = "Updated docs",
            description = "Updated docs description",
            estimatedMinutes = 45,
            expectedOutcome = "Understands updated docs",
            status = StepStatus.FINISHED,
            completedAt = createdAt,
            skipReason = null,
        )

        every { onboardingService.updateOnboardingStep(stepId, request) } returns response

        mockMvc.put("/api/v1/onboarding/steps/$stepId") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(stepId.toString()) }
            jsonPath("$.position") { value(2) }
            jsonPath("$.status") { value("FINISHED") }
        }

        verify(exactly = 1) { onboardingService.updateOnboardingStep(stepId, request) }
    }

    @Test
    fun `deleteOnboardingStepForPhaseId should return 200`() {
        every { onboardingService.deleteOnboardingStep(stepId) } just Runs

        mockMvc.delete("/api/v1/onboarding/steps/$stepId")
            .andExpect {
                status { isNoContent() }
            }

        verify(exactly = 1) { onboardingService.deleteOnboardingStep(stepId) }
    }

    // -------------------------------------------------------------------------
    // Tasks
    // -------------------------------------------------------------------------

    @Test
    fun `createOnboardingTask should return 200 and created task`() {
        val request = CreateOnboardingTaskRequest(
            position = 1,
            title = "Create account",
            description = "Create an internal account",
        )
        val response = CreateOnboardingTaskResponse(
            id = taskId,
            stepId = stepId,
            position = 1,
            title = "Create account",
            description = "Create an internal account",
            finished = false,
        )

        every { onboardingService.createOnboardingTaskForStepId(stepId, request) } returns response

        mockMvc.post("/api/v1/onboarding/steps/$stepId/tasks") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(taskId.toString()) }
            jsonPath("$.stepId") { value(stepId.toString()) }
            jsonPath("$.finished") { value(false) }
        }

        verify(exactly = 1) { onboardingService.createOnboardingTaskForStepId(stepId, request) }
    }

    @Test
    fun `getOnboardingTasks should return 200 and all tasks`() {
        val response = listOf(
            GetOnboardingTasksResponse(
                id = taskId,
                stepId = stepId,
                position = 1,
                title = "Create account",
                description = "Create an internal account",
                finished = false,
            ),
        )

        every { onboardingService.getOnboardingTasks() } returns response

        mockMvc.get("/api/v1/onboarding/tasks")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(taskId.toString()) }
                jsonPath("$[0].stepId") { value(stepId.toString()) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingTasks() }
    }

    @Test
    fun `getOnboardingTasksByStepId should return 200 and tasks for step`() {
        val response = listOf(
            GetOnboardingTaskResponse(
                id = taskId,
                stepId = stepId,
                position = 1,
                title = "Create account",
                description = "Create an internal account",
                finished = false,
            ),
        )

        every { onboardingService.getOnboardingTasksByStepId(stepId) } returns response

        mockMvc.get("/api/v1/onboarding/steps/$stepId/tasks")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(taskId.toString()) }
                jsonPath("$[0].stepId") { value(stepId.toString()) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingTasksByStepId(stepId) }
    }

    @Test
    fun `getOnboardingTask should return 200 and task`() {
        val response = GetOnboardingTaskResponse(
            id = taskId,
            stepId = stepId,
            position = 1,
            title = "Create account",
            description = "Create an internal account",
            finished = false,
        )

        every { onboardingService.getOnboardingTask(taskId) } returns response

        mockMvc.get("/api/v1/onboarding/tasks/$taskId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(taskId.toString()) }
                jsonPath("$.stepId") { value(stepId.toString()) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingTask(taskId) }
    }

    @Test
    fun `updateOnboardingTask should return 200 and updated task`() {
        val request = UpdateOnboardingTaskRequest(
            position = 2,
            title = "Updated account task",
            description = "Updated account task description",
            finished = true,
        )
        val response = UpdateOnboardingTaskResponse(
            id = taskId,
            stepId = stepId,
            position = 2,
            title = "Updated account task",
            description = "Updated account task description",
            finished = true,
        )

        every { onboardingService.updateOnboardingTask(taskId, request) } returns response

        mockMvc.put("/api/v1/onboarding/tasks/$taskId") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(taskId.toString()) }
            jsonPath("$.position") { value(2) }
            jsonPath("$.finished") { value(true) }
        }

        verify(exactly = 1) { onboardingService.updateOnboardingTask(taskId, request) }
    }

    @Test
    fun `deleteOnboardingTaskForStepId should return 200`() {
        every { onboardingService.deleteOnboardingTask(taskId) } just Runs

        mockMvc.delete("/api/v1/onboarding/tasks/$taskId")
            .andExpect {
                status { isNoContent() }
            }

        verify(exactly = 1) { onboardingService.deleteOnboardingTask(taskId) }
    }

    // -------------------------------------------------------------------------
    // Resources
    // -------------------------------------------------------------------------

    @Test
    fun `createOnboardingResourceForStepId should return 200 and created resource`() {
        val request = CreateOnboardingResourceRequest(
            title = "Documentation",
            description = "Internal documentation",
            url = "https://example.com/docs",
        )
        val response = CreateOnboardingResourceResponse(
            id = resourceId,
            stepId = stepId,
            title = "Documentation",
            description = "Internal documentation",
            url = "https://example.com/docs",
        )

        every { onboardingService.createOnboardingResourceForStepId(stepId, request) } returns response

        mockMvc.post("/api/v1/onboarding/steps/$stepId/resources") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(resourceId.toString()) }
            jsonPath("$.stepId") { value(stepId.toString()) }
            jsonPath("$.url") { value("https://example.com/docs") }
        }

        verify(exactly = 1) { onboardingService.createOnboardingResourceForStepId(stepId, request) }
    }

    @Test
    fun `getOnboardingResources should return 200 and all resources`() {
        val response = listOf(
            GetOnboardingResourcesResponse(
                id = resourceId,
                stepId = stepId,
                title = "Documentation",
                description = "Internal documentation",
                url = "https://example.com/docs",
            ),
        )

        every { onboardingService.getOnboardingResources() } returns response

        mockMvc.get("/api/v1/onboarding/resources")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(resourceId.toString()) }
                jsonPath("$[0].stepId") { value(stepId.toString()) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingResources() }
    }

    @Test
    fun `getOnboardingResourcesByStepId should return 200 and resources for step`() {
        val response = listOf(
            GetOnboardingResourceResponse(
                id = resourceId,
                stepId = stepId,
                title = "Documentation",
                description = "Internal documentation",
                url = "https://example.com/docs",
            ),
        )

        every { onboardingService.getOnboardingResourceByStepId(stepId) } returns response

        mockMvc.get("/api/v1/onboarding/steps/$stepId/resources")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].id") { value(resourceId.toString()) }
                jsonPath("$[0].stepId") { value(stepId.toString()) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingResourceByStepId(stepId) }
    }

    @Test
    fun `getOnboardingResource should return 200 and resource`() {
        val response = GetOnboardingResourceResponse(
            id = resourceId,
            stepId = stepId,
            title = "Documentation",
            description = "Internal documentation",
            url = "https://example.com/docs",
        )

        every { onboardingService.getOnboardingResource(resourceId) } returns response

        mockMvc.get("/api/v1/onboarding/resources/$resourceId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(resourceId.toString()) }
                jsonPath("$.stepId") { value(stepId.toString()) }
            }

        verify(exactly = 1) { onboardingService.getOnboardingResource(resourceId) }
    }

    @Test
    fun `updateOnboardingResource should return 200 and updated resource`() {
        val request = UpdateOnboardingResourceRequest(
            title = "Updated documentation",
            description = "Updated internal documentation",
            url = "https://example.com/updated-docs",
        )
        val response = UpdateOnboardingResourceResponse(
            id = resourceId,
            stepId = stepId,
            title = "Updated documentation",
            description = "Updated internal documentation",
            url = "https://example.com/updated-docs",
        )

        every { onboardingService.updateOnboardingResource(resourceId, request) } returns response

        mockMvc.put("/api/v1/onboarding/resources/$resourceId") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(resourceId.toString()) }
            jsonPath("$.title") { value("Updated documentation") }
            jsonPath("$.url") { value("https://example.com/updated-docs") }
        }

        verify(exactly = 1) { onboardingService.updateOnboardingResource(resourceId, request) }
    }

    @Test
    fun `deleteOnboardingResourceForStepId should return 200`() {
        every { onboardingService.deleteOnboardingResource(resourceId) } just Runs

        mockMvc.delete("/api/v1/onboarding/resources/$resourceId")
            .andExpect {
                status { isNoContent() }
            }

        verify(exactly = 1) { onboardingService.deleteOnboardingResource(resourceId) }
    }
}