package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.onboarding.repository.KnowledgeRequestRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Erases the questions a deleted hire escalated to their manager.
 *
 * Canonical answers are the team's, not the hire's, and stay. Runs in the deleting transaction.
 */
@Component
class KnowledgeUserDeletedListener(
    private val knowledgeRequestRepository: KnowledgeRequestRepository,
) {
    @EventListener
    @Transactional
    fun onUserDeleted(event: UserDeletedEvent) {
        knowledgeRequestRepository.deleteAllByHireId(event.userId)
    }
}
