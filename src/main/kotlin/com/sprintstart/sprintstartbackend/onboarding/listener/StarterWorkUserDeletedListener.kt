package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.onboarding.repository.GithubHistoryPriorRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGoalRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Erases what starter-work matching learned about a deleted user: the level they were placed at,
 * what they were working toward, and the prior read from their GitHub history.
 *
 * The competency catalogue and the starter-work pool are the team's, not the user's, and stay.
 * Runs in the deleting transaction.
 */
@Component
class StarterWorkUserDeletedListener(
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val userGoalRepository: UserGoalRepository,
    private val githubHistoryPriorRepository: GithubHistoryPriorRepository,
) {
    @EventListener
    @Transactional
    fun onUserDeleted(event: UserDeletedEvent) {
        userCompetencyStateRepository.deleteAllByUserId(event.userId)
        userGoalRepository.deleteAllByUserId(event.userId)
        githubHistoryPriorRepository.deleteAllByUserId(event.userId)
    }
}
