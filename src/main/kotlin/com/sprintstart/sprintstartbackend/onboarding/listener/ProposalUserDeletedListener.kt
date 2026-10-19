package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyActionProposalRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Erases the actions an assistant proposed to a deleted user, and what they decided about them.
 *
 * Runs in the deleting transaction.
 */
@Component
class ProposalUserDeletedListener(
    private val buddyActionProposalRepository: BuddyActionProposalRepository,
) {
    @EventListener
    @Transactional
    fun onUserDeleted(event: UserDeletedEvent) {
        buddyActionProposalRepository.deleteAllByUserId(event.userId)
    }
}
