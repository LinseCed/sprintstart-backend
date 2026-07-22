package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyToolCallDto
import com.sprintstart.sprintstartbackend.onboarding.model.request.buddy.BuddyActionRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.orientation.MyOrientationResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.MyTaskZeroResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkTaskProposalResponse
import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.external.dto.ProjectDto
import com.sprintstart.sprintstartbackend.user.external.dto.UserDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class BuddyActionServiceTest {
    private val taskZeroService: TaskZeroService = mockk()
    private val taskOrientationService: TaskOrientationService = mockk()
    private val onboardingBuddyService: OnboardingBuddyService = mockk(relaxed = true)
    private val knowledgeBaseService: KnowledgeBaseService = mockk(relaxed = true)
    private val userApi: UserApi = mockk()
    private val service = BuddyActionService(
        taskZeroService,
        taskOrientationService,
        onboardingBuddyService,
        knowledgeBaseService,
        userApi,
    )

    private val userId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val authId = "auth|hire"

    private fun userWith(vararg projects: ProjectDto) = UserDto(
        id = userId,
        username = "hire",
        firstname = "Sam",
        lastname = "Hire",
        avatarUrl = null,
        profileIcon = null,
        projects = projects.toSet(),
        projectRoles = emptyList(),
    )

    private val oneProject = ProjectDto(projectId, "Checkout", null)

    private fun onOneProject() {
        every { userApi.getUsersByIds(listOf(userId)) } returns listOf(userWith(oneProject))
    }

    private fun asHire() {
        every { userApi.getUserIdByAuthId(authId) } returns Optional.of(userId)
    }

    private val jwt: Jwt = mockk<Jwt>().also { every { it.subject } returns authId }

    private fun call(name: String, args: Map<String, String> = emptyMap()) = BuddyToolCallDto(
        id = "c0",
        name = name,
        arguments = buildJsonObject { args.forEach { (k, v) -> put(k, v) } },
    )

    private fun taskZero(title: String?) = MyTaskZeroResponse(
        task = title?.let {
            StarterWorkTaskProposalResponse(
                id = UUID.randomUUID(),
                sourceId = "src",
                title = it,
                summary = null,
                rationale = null,
                sourceUrl = null,
                competencyKeys = emptyList(),
                status = ProposalStatus.APPROVED,
                taskZeroEligible = true,
            )
        },
        assignedAt = title?.let { Instant.EPOCH },
        noneAvailable = title == null,
        loopProven = false,
    )

    // -- specs / dispatch -------------------------------------------------------------------------

    @Test
    fun `exposes exactly the four action tools`() {
        assertThat(service.actionSpecs().map { it.name }).containsExactlyInAnyOrder(
            "flag_to_pm",
            "claim_task_zero",
            "open_orientation",
            "log_buddy_contact",
        )
    }

    @Test
    fun `recognises action tools and rejects read tools`() {
        assertThat(service.isAction("claim_task_zero")).isTrue()
        assertThat(service.isAction("get_my_metrics")).isFalse()
    }

    // -- propose (must never mutate) --------------------------------------------------------------

    @Test
    fun `proposes claim Task 0 with its confirm label and no mutation`() {
        onOneProject()

        val outcome = service.propose(call("claim_task_zero"), userId)

        assertThat(outcome.proposal?.action).isEqualTo("claim_task_zero")
        assertThat(outcome.proposal?.label).isEqualTo("Start Task 0")
        assertThat(outcome.toolResult).contains("confirm")
        // Proposing must not touch the assignment.
        verify(exactly = 0) { taskZeroService.getForHire(any(), any()) }
    }

    @Test
    fun `carries the composed question through a flag-to-PM proposal`() {
        onOneProject()

        val outcome = service.propose(
            call("flag_to_pm", mapOf("question" to "How do we deploy?")),
            userId,
        )

        assertThat(outcome.proposal?.action).isEqualTo("flag_to_pm")
        assertThat(outcome.proposal?.question).isEqualTo("How do we deploy?")
    }

    @Test
    fun `does not propose flag-to-PM without a question`() {
        onOneProject()

        val outcome = service.propose(call("flag_to_pm"), userId)

        assertThat(outcome.proposal).isNull()
        assertThat(outcome.toolResult).contains("No question")
    }

    @Test
    fun `does not propose an action when the hire is on no project`() {
        every { userApi.getUsersByIds(listOf(userId)) } returns listOf(userWith())

        val outcome = service.propose(call("claim_task_zero"), userId)

        assertThat(outcome.proposal).isNull()
        assertThat(outcome.toolResult).contains("not on a project")
    }

    @Test
    fun `does not propose an action when the hire is on multiple projects`() {
        every { userApi.getUsersByIds(listOf(userId)) } returns listOf(
            userWith(oneProject, ProjectDto(UUID.randomUUID(), "Billing", null)),
        )

        val outcome = service.propose(call("open_orientation"), userId)

        assertThat(outcome.proposal).isNull()
        assertThat(outcome.toolResult).contains("more than one project")
    }

    // -- perform (the confirm round-trip) ---------------------------------------------------------

    @Test
    fun `claiming Task 0 assigns it and reports the title`() = runTest {
        asHire()
        onOneProject()
        every { taskZeroService.getForHire(userId, projectId) } returns taskZero("Fix the login redirect")

        val result = service.perform(BuddyActionRequest(action = "claim_task_zero"), jwt)

        assertThat(result.ok).isTrue()
        assertThat(result.message).contains("Fix the login redirect")
    }

    @Test
    fun `claiming Task 0 legibly reports when none is eligible`() = runTest {
        asHire()
        onOneProject()
        every { taskZeroService.getForHire(userId, projectId) } returns taskZero(null)

        val result = service.perform(BuddyActionRequest(action = "claim_task_zero"), jwt)

        assertThat(result.ok).isFalse()
        assertThat(result.message).contains("no eligible Task 0")
    }

    @Test
    fun `flagging to the PM escalates the question`() = runTest {
        asHire()
        onOneProject()

        val result = service.perform(
            BuddyActionRequest(action = "flag_to_pm", question = "How do we deploy?"),
            jwt,
        )

        assertThat(result.ok).isTrue()
        verify(exactly = 1) { knowledgeBaseService.escalate(authId, projectId, "How do we deploy?") }
    }

    @Test
    fun `flagging to the PM with no question never escalates`() = runTest {
        asHire()
        onOneProject()

        val result = service.perform(BuddyActionRequest(action = "flag_to_pm", question = "  "), jwt)

        assertThat(result.ok).isFalse()
        verify(exactly = 0) { knowledgeBaseService.escalate(any(), any(), any()) }
    }

    @Test
    fun `logging buddy contact records it on the hire's behalf`() = runTest {
        asHire()
        onOneProject()

        val result = service.perform(
            BuddyActionRequest(action = "log_buddy_contact", note = "quick sync"),
            jwt,
        )

        assertThat(result.ok).isTrue()
        verify(exactly = 1) {
            onboardingBuddyService.logContact(projectId, userId, userId, null, "quick sync")
        }
    }

    @Test
    fun `opening orientation reports the packet is ready`() = runTest {
        asHire()
        onOneProject()
        coEvery { taskOrientationService.getForHire(userId, projectId) } returns MyOrientationResponse(
            taskId = UUID.randomUUID(),
            taskTitle = "Fix the login redirect",
            taskUrl = null,
            packet = mockk(),
            reason = null,
        )

        val result = service.perform(BuddyActionRequest(action = "open_orientation"), jwt)

        assertThat(result.ok).isTrue()
        assertThat(result.message).contains("Fix the login redirect")
    }

    @Test
    fun `opening orientation relays the reason when there is no packet`() = runTest {
        asHire()
        onOneProject()
        coEvery { taskOrientationService.getForHire(userId, projectId) } returns MyOrientationResponse(
            taskId = null,
            taskTitle = null,
            taskUrl = null,
            packet = null,
            reason = "You have no current task yet",
        )

        val result = service.perform(BuddyActionRequest(action = "open_orientation"), jwt)

        assertThat(result.ok).isFalse()
        assertThat(result.message).contains("no current task")
    }

    @Test
    fun `an unrecognised action is a handled failure, not an error`() = runTest {
        asHire()

        val result = service.perform(BuddyActionRequest(action = "launch_rockets"), jwt)

        assertThat(result.ok).isFalse()
        assertThat(result.message).contains("isn't recognised")
    }

    @Test
    fun `a precondition failure downstream comes back as a legible reason`() = runTest {
        asHire()
        onOneProject()
        every { taskZeroService.getForHire(userId, projectId) } throws
            ResponseStatusException(HttpStatus.NOT_FOUND, "You are not a member of that project.")

        val result = service.perform(BuddyActionRequest(action = "claim_task_zero"), jwt)

        assertThat(result.ok).isFalse()
        assertThat(result.message).contains("not a member")
    }
}
