package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPhase
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingFeedbackRepository
import com.sprintstart.sprintstartbackend.shared.scheduler.ScheduledExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

class ContentQualityServiceTest {
    private val onboardingFeedbackRepository: OnboardingFeedbackRepository = mockk()
    private val verificationService: VerificationService = mockk()
    private val scheduledExecutor: ScheduledExecutor = mockk()
    private val service = ContentQualityService(onboardingFeedbackRepository, verificationService, scheduledExecutor)

    private val stepId = UUID.randomUUID()

    private fun makeStep(): OnboardingStep {
        val path = OnboardingPath(userId = UUID.randomUUID())
        val phase =
            OnboardingPhase(id = UUID.randomUUID(), path = path, position = 0, title = "Phase", description = "Desc")
        return OnboardingStep(
            id = stepId,
            phase = phase,
            position = 0,
            title = "Step",
            description = "Desc",
            type = StepType.DOCUMENT,
            estimatedMinutes = 30,
            expectedOutcome = "Outcome",
            status = StepStatus.WAITING,
        )
    }

    @Nested
    inner class CheckAndTriggerRegeneration {
        @Test
        fun `does not trigger regeneration below the unhelpful threshold`() {
            val step = makeStep()
            every { onboardingFeedbackRepository.countByStepIdAndHelpfulFalse(stepId) } returns 2L

            service.checkAndTriggerRegeneration(step)

            verify(exactly = 0) { scheduledExecutor.launch(any(), any()) }
        }

        @Test
        fun `triggers a forced regeneration exactly at the unhelpful threshold`() = runTest {
            val step = makeStep()
            every { onboardingFeedbackRepository.countByStepIdAndHelpfulFalse(stepId) } returns 3L
            val job = slot<suspend () -> Unit>()
            every { scheduledExecutor.launch(any(), capture(job)) } returns Unit
            coEvery { verificationService.synthesizeContent(stepId, forceRegenerate = true) } returns Unit

            service.checkAndTriggerRegeneration(step)
            job.captured.invoke()

            coVerify(exactly = 1) { verificationService.synthesizeContent(stepId, forceRegenerate = true) }
        }

        @Test
        fun `does not re-trigger once the threshold has already been crossed`() {
            val step = makeStep()
            every { onboardingFeedbackRepository.countByStepIdAndHelpfulFalse(stepId) } returns 4L

            service.checkAndTriggerRegeneration(step)

            verify(exactly = 0) { scheduledExecutor.launch(any(), any()) }
        }
    }
}
