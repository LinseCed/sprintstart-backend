package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingFeedbackRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPathRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.QuestionAttemptRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Erases a deleted user's onboarding path, their answers to its checks, and the feedback they gave.
 *
 * Deleting the path cascades to its phases and steps, and with the steps to their tasks, resources,
 * skip requests and the feedback given on them. Feedback not tied to a step and the check answers hang
 * on the user alone, so they are deleted by user. Runs in the deleting transaction.
 */
@Component
class PathUserDeletedListener(
    private val onboardingPathRepository: OnboardingPathRepository,
    private val questionAttemptRepository: QuestionAttemptRepository,
    private val onboardingFeedbackRepository: OnboardingFeedbackRepository,
) {
    @EventListener
    @Transactional
    fun onUserDeleted(event: UserDeletedEvent) {
        onboardingFeedbackRepository.deleteAllByUserId(event.userId)
        questionAttemptRepository.deleteAllByUserId(event.userId)
        onboardingPathRepository.deleteByUserId(event.userId)
    }
}
