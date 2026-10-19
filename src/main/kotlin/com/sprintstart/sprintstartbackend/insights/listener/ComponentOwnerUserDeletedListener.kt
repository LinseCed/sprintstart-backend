package com.sprintstart.sprintstartbackend.insights.listener

import com.sprintstart.sprintstartbackend.insights.repository.ComponentOwnerRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Removes a deleted user as owner of their components.
 *
 * Otherwise knowledge gaps on those components would keep naming an owner nobody can reach. The
 * components themselves and their other owners stay. Runs in the deleting transaction.
 */
@Component
class ComponentOwnerUserDeletedListener(
    private val componentOwnerRepository: ComponentOwnerRepository,
) {
    @EventListener
    @Transactional
    fun onUserDeleted(event: UserDeletedEvent) {
        componentOwnerRepository.deleteAllByUserId(event.userId)
    }
}
