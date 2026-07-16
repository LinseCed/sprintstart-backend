package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphVersion
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CompetencyGraphVersionServiceTest {
    private val repository: CompetencyGraphVersionRepository = mockk()
    private val service = CompetencyGraphVersionService(repository)

    @Nested
    inner class CurrentVersion {
        @Test
        fun `defaults to 1 when no row exists`() {
            every { repository.findTopByOrderByVersionDesc() } returns null

            assertThat(service.currentVersion()).isEqualTo(1)
        }

        @Test
        fun `returns the stored version`() {
            every { repository.findTopByOrderByVersionDesc() } returns CompetencyGraphVersion(version = 5)

            assertThat(service.currentVersion()).isEqualTo(5)
        }
    }

    @Nested
    inner class Bump {
        @Test
        fun `creates the first row at version 1 when none exists`() {
            every { repository.findTopByOrderByVersionDesc() } returns null
            val savedSlot = slot<CompetencyGraphVersion>()
            every { repository.save(capture(savedSlot)) } answers { savedSlot.captured }

            val result = service.bump()

            assertThat(result).isEqualTo(1)
            assertThat(savedSlot.captured.version).isEqualTo(1)
        }

        @Test
        fun `increments an existing row in place`() {
            val existing = CompetencyGraphVersion(version = 3)
            every { repository.findTopByOrderByVersionDesc() } returns existing

            val result = service.bump()

            assertThat(result).isEqualTo(4)
            assertThat(existing.version).isEqualTo(4)
            verify(exactly = 0) { repository.save(any()) }
        }
    }
}
