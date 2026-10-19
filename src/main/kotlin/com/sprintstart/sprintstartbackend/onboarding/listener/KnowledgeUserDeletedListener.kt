package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.onboarding.repository.KnowledgeRequestRepository
import com.sprintstart.sprintstartbackend.user.external.events.OnUserDeleted
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.stereotype.Component

/**
 * Erases the questions a deleted hire escalated to their manager.
 *
 * Canonical answers are the team's, not the hire's, and stay.
 */
@Component
class KnowledgeUserDeletedListener(
    private val knowledgeRequestRepository: KnowledgeRequestRepository,
) {
    @OnUserDeleted
    fun onUserDeleted(event: UserDeletedEvent) {
        knowledgeRequestRepository.deleteAllByHireId(event.userId)
    }
}
