package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.onboarding.repository.ArrivalStepStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.AttestationRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Erases a deleted hire's arrival progress and the attestations about them.
 *
 * Attestations this person *granted* stay. They are evidence about somebody else's work, and
 * removing them would silently lower a colleague's ledger because an unrelated account was closed.
 * Runs in the deleting transaction.
 */
@Component
class ArrivalUserDeletedListener(
    private val arrivalStepStateRepository: ArrivalStepStateRepository,
    private val attestationRepository: AttestationRepository,
) {
    @EventListener
    @Transactional
    fun onUserDeleted(event: UserDeletedEvent) {
        arrivalStepStateRepository.deleteAllByUserId(event.userId)
        attestationRepository.deleteAllByHireId(event.userId)
    }
}
