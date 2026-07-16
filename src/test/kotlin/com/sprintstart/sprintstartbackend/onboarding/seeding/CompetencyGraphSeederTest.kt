package com.sprintstart.sprintstartbackend.onboarding.seeding

import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.service.CompetencyGraphVersionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationArguments

class CompetencyGraphSeederTest {
    private val competencyRepository: CompetencyRepository = mockk()
    private val competencyEdgeRepository: CompetencyEdgeRepository = mockk()
    private val competencyGraphVersionService: CompetencyGraphVersionService = mockk(relaxed = true)
    private val seeder = CompetencyGraphSeeder(
        competencyRepository,
        competencyEdgeRepository,
        competencyGraphVersionService,
    )
    private val args: ApplicationArguments = mockk()

    @Test
    fun `records each insert and bumps the graph version when seeding into an empty graph`() {
        every { competencyRepository.existsByKey(any()) } returns false
        every { competencyRepository.save(any<Competency>()) } answers { firstArg() }
        every { competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(any(), any(), any()) } returns false
        every { competencyEdgeRepository.save(any<CompetencyEdge>()) } answers { firstArg() }

        seeder.run(args)

        verify(exactly = 7) { competencyGraphVersionService.recordNodeAdded(any()) }
        verify(exactly = 4) { competencyGraphVersionService.recordEdgeAdded(any(), any(), any()) }
        verify(exactly = 1) { competencyGraphVersionService.bump() }
    }

    @Test
    fun `does not record changes or bump the version when everything is already seeded`() {
        every { competencyRepository.existsByKey(any()) } returns true
        every { competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(any(), any(), any()) } returns true

        seeder.run(args)

        verify(exactly = 0) { competencyRepository.save(any()) }
        verify(exactly = 0) { competencyEdgeRepository.save(any()) }
        verify(exactly = 0) { competencyGraphVersionService.recordNodeAdded(any()) }
        verify(exactly = 0) { competencyGraphVersionService.recordEdgeAdded(any(), any(), any()) }
        verify(exactly = 0) { competencyGraphVersionService.bump() }
    }
}
