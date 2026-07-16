package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGraphPinRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserSessionStartedEvent
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Advances a hire's [UserGraphPin] to the graph's current version at their next session start.
 *
 * This is the reconciliation half of tiered graph versioning: [EffectiveGraphResolver] already
 * shows ADDITIVE/INVARIANT changes immediately regardless of the pin, so advancing the pin here
 * only changes what becomes visible for STRUCTURAL changes that were being held back. Never
 * touches [com.sprintstart.sprintstartbackend.onboarding.model.entity.UserCompetencyState] --
 * the durable ledger is untouched by reconciliation, per the Phase 0c boundary.
 */
@Service
class GraphReconciliationService(
    private val pinRepository: UserGraphPinRepository,
    private val versionService: CompetencyGraphVersionService,
) {
    @ApplicationModuleListener
    fun on(event: UserSessionStartedEvent) {
        reconcile(event.userId)
    }

    /**
     * Advances the hire's pin to the graph's current version, creating it first if this is their
     * first-ever reconciliation. A no-op when the pin is already current.
     *
     * @param userId Internal SprintStart user identifier.
     */
    @Transactional
    fun reconcile(userId: UUID) {
        val currentVersion = versionService.currentVersion()
        val pin = pinRepository.findByUserId(userId)

        if (pin == null) {
            pinRepository.save(UserGraphPin(userId = userId, pinnedVersion = currentVersion))
            return
        }

        if (pin.pinnedVersion == currentVersion) return

        pin.pinnedVersion = currentVersion
        pin.updatedAt = Instant.now()
    }
}
