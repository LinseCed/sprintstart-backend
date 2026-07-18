package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingStep
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingFeedbackRepository
import com.sprintstart.sprintstartbackend.shared.scheduler.ScheduledExecutor
import org.springframework.stereotype.Service

/**
 * Closes the content-quality loop: once a step's lesson accumulates enough "not helpful"
 * [com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingFeedback], triggers a
 * forced AI resynthesis of that lesson via [VerificationService.synthesizeContent].
 *
 * Fires exactly once per threshold crossing (`count == threshold`, not `>=`) -- the unhelpful
 * count is cumulative and never resets, so an `>=` check would re-trigger a full forced
 * resynthesis on every subsequent unhelpful feedback for the same step. The actual AI call is
 * dispatched via [ScheduledExecutor] so the calling feedback-submission request never blocks on
 * AI latency, mirroring how [com.sprintstart.sprintstartbackend.connectors.github.GithubScheduledExecutor]
 * launches async work from a synchronous call site.
 */
@Service
class ContentQualityService(
    private val onboardingFeedbackRepository: OnboardingFeedbackRepository,
    private val verificationService: VerificationService,
    private val scheduledExecutor: ScheduledExecutor,
) {
    fun checkAndTriggerRegeneration(step: OnboardingStep) {
        val unhelpfulCount = onboardingFeedbackRepository.countByStepIdAndHelpfulFalse(step.id)
        if (unhelpfulCount == UNHELPFUL_FEEDBACK_THRESHOLD) {
            scheduledExecutor.launch("Regenerating lesson content for step ${step.id}") {
                verificationService.synthesizeContent(step.id, forceRegenerate = true)
            }
        }
    }

    private companion object {
        const val UNHELPFUL_FEEDBACK_THRESHOLD = 3L
    }
}
