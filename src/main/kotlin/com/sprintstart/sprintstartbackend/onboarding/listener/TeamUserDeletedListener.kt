package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyTeamMessageRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddyTeamSessionRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Erases a deleted manager's team-mode conversations on every project.
 *
 * They are the manager's own: what they asked about their team, and the note the model kept of it.
 * Messages go before their sessions. Runs in the deleting transaction.
 *
 * Other managers' team conversations that *mention* the deleted user stay. They are those managers'
 * record, like notes they took. This is a decision, not an oversight: the event carries only the user
 * id and their memberships are already gone, so finding those mentions would mean erasing every
 * manager's whole conversation on those projects.
 */
@Component
class TeamUserDeletedListener(
    private val buddyTeamSessionRepository: BuddyTeamSessionRepository,
    private val buddyTeamMessageRepository: BuddyTeamMessageRepository,
) {
    @EventListener
    @Transactional
    fun onUserDeleted(event: UserDeletedEvent) {
        buddyTeamSessionRepository.findAllByUserId(event.userId).forEach {
            buddyTeamMessageRepository.deleteAllBySessionId(it.id)
        }
        buddyTeamSessionRepository.deleteAllByUserId(event.userId)
    }
}
