package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGraphPinRepository
import com.sprintstart.sprintstartbackend.user.external.events.UserSessionStartedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

class GraphReconciliationServiceTest {
    private val pinRepository: UserGraphPinRepository = mockk()
    private val versionService: CompetencyGraphVersionService = mockk()
    private val service = GraphReconciliationService(pinRepository, versionService)

    private val userId = UUID.randomUUID()

    @Nested
    inner class Reconcile {
        @Test
        fun `creates a pin at the current version when the hire has never been reconciled`() {
            every { versionService.currentVersion() } returns 5
            every { pinRepository.findByUserId(userId) } returns null
            val savedSlot = slot<UserGraphPin>()
            every { pinRepository.save(capture(savedSlot)) } answers { savedSlot.captured }

            service.reconcile(userId)

            assertThat(savedSlot.captured.userId).isEqualTo(userId)
            assertThat(savedSlot.captured.pinnedVersion).isEqualTo(5)
        }

        @Test
        fun `advances an existing pin to the current version`() {
            every { versionService.currentVersion() } returns 5
            val pin = UserGraphPin(userId = userId, pinnedVersion = 3)
            every { pinRepository.findByUserId(userId) } returns pin

            service.reconcile(userId)

            assertThat(pin.pinnedVersion).isEqualTo(5)
        }

        @Test
        fun `is a no-op when the pin is already current`() {
            every { versionService.currentVersion() } returns 5
            val pin = UserGraphPin(userId = userId, pinnedVersion = 5)
            every { pinRepository.findByUserId(userId) } returns pin

            service.reconcile(userId)

            assertThat(pin.pinnedVersion).isEqualTo(5)
            verify(exactly = 0) { pinRepository.save(any()) }
        }
    }

    @Nested
    inner class OnUserSessionStartedEvent {
        @Test
        fun `reconciles the event's user`() {
            every { versionService.currentVersion() } returns 2
            every { pinRepository.findByUserId(userId) } returns null
            every { pinRepository.save(any()) } answers { firstArg() }

            service.on(UserSessionStartedEvent(userId))

            verify(exactly = 1) { pinRepository.findByUserId(userId) }
        }
    }
}
