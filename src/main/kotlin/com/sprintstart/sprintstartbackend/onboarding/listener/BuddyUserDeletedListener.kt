package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddySessionRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Erases a deleted user's conversations with their buddy.
 *
 * The conversation is the most of a person the system holds: every message they sent, and the
 * running note the model keeps about them. Messages go before the sessions they belong to, so no row
 * is left that nothing can reach. Runs in the deleting transaction, so a failed deletion takes the
 * erasure with it.
 */
@Component
class BuddyUserDeletedListener(
    private val buddySessionRepository: BuddySessionRepository,
    private val buddyMessageRepository: BuddyMessageRepository,
) {
    @EventListener
    @Transactional
    fun onUserDeleted(event: UserDeletedEvent) {
        buddySessionRepository.findByUserId(event.userId).forEach {
            buddyMessageRepository.deleteAllBySessionId(it.id)
        }
        buddySessionRepository.deleteAllByUserId(event.userId)
    }
}
