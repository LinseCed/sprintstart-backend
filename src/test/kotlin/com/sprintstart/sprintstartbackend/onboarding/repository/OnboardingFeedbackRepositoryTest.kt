package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.StepType
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingFeedback
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPhase
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.encrypt.BytesEncryptor
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Repository slice test for the content-quality loop's aggregation query. See
 * [CompetencyGraphRepositoryTest] for why [CryptoTestConfig] is needed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OnboardingFeedbackRepositoryTest.CryptoTestConfig::class)
@ActiveProfiles("test")
class OnboardingFeedbackRepositoryTest
    @Autowired
    constructor(
        private val onboardingFeedbackRepository: OnboardingFeedbackRepository,
        private val onboardingPathRepository: OnboardingPathRepository,
    ) {
        private fun persistStep(): OnboardingStep {
            val path = OnboardingPath(userId = UUID.randomUUID())
            val phase =
                OnboardingPhase(path = path, position = 0, title = "Phase", description = "Desc")
            val step = OnboardingStep(
                phase = phase,
                position = 0,
                title = "Step",
                description = "Desc",
                type = StepType.DOCUMENT,
                estimatedMinutes = 30,
                expectedOutcome = "Outcome",
                status = StepStatus.WAITING,
            )
            phase.steps.add(step)
            path.phases.add(phase)
            onboardingPathRepository.saveAndFlush(path)
            return step
        }

        @Test
        fun `counts only not-helpful feedback for the targeted step`() {
            val step = persistStep()
            val otherStep = persistStep()

            onboardingFeedbackRepository.saveAndFlush(
                OnboardingFeedback(userId = UUID.randomUUID(), step = step, helpful = false, message = "meh"),
            )
            onboardingFeedbackRepository.saveAndFlush(
                OnboardingFeedback(userId = UUID.randomUUID(), step = step, helpful = false, message = "meh again"),
            )
            onboardingFeedbackRepository.saveAndFlush(
                OnboardingFeedback(userId = UUID.randomUUID(), step = step, helpful = true, message = "great"),
            )
            onboardingFeedbackRepository.saveAndFlush(
                OnboardingFeedback(userId = UUID.randomUUID(), step = step, helpful = null, message = "no rating"),
            )
            onboardingFeedbackRepository.saveAndFlush(
                OnboardingFeedback(userId = UUID.randomUUID(), step = otherStep, helpful = false, message = "meh"),
            )

            val count = onboardingFeedbackRepository.countByStepIdAndHelpfulFalse(step.id)

            assertThat(count).isEqualTo(2L)
        }

        @TestConfiguration
        class CryptoTestConfig {
            @Bean
            fun bytesEncryptor(): BytesEncryptor = Encryptors.stronger("deadbeef", "deadbeef")
        }
    }
