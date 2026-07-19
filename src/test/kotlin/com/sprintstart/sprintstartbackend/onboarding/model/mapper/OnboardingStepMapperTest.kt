package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepPageKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPhase
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingTask
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class OnboardingStepMapperTest {
    private fun step(content: String?, withTask: Boolean): OnboardingStep {
        val path = OnboardingPath(userId = UUID.randomUUID())
        val phase = OnboardingPhase(path = path, position = 0, title = "Phase", description = "")
        val step = OnboardingStep(
            phase = phase,
            position = 0,
            title = "Step",
            description = "Desc",
            type = StepType.DOCUMENT,
            estimatedMinutes = 20,
            expectedOutcome = "Outcome",
            status = StepStatus.WAITING,
            content = content,
        )
        if (withTask) {
            step.tasks.add(OnboardingTask(step = step, position = 0, title = "Do the thing", description = ""))
        }
        return step
    }

    @Test
    fun `derives Learn Practice Verify pages in order when all three are present`() {
        val pages = step(content = "# Lesson", withTask = true).derivePages(hasVerification = true)

        assertThat(pages.map { it.kind }).containsExactly(
            StepPageKind.LESSON,
            StepPageKind.TASK,
            StepPageKind.VERIFY,
        )
        assertThat(pages.first { it.kind == StepPageKind.LESSON }.content).isEqualTo("# Lesson")
    }

    @Test
    fun `a lesson-only step derives a single Learn page`() {
        val pages = step(content = "# Lesson", withTask = false).derivePages(hasVerification = false)

        assertThat(pages.map { it.kind }).containsExactly(StepPageKind.LESSON)
    }

    @Test
    fun `blank lesson content produces no Learn page`() {
        val pages = step(content = "   ", withTask = false).derivePages(hasVerification = true)

        assertThat(pages.map { it.kind }).containsExactly(StepPageKind.VERIFY)
    }

    @Test
    fun `a step with no content, tasks or verification derives no pages`() {
        val pages = step(content = null, withTask = false).derivePages(hasVerification = false)

        assertThat(pages).isEmpty()
    }
}
