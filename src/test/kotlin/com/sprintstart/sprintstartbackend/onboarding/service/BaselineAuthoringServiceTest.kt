package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.request.blueprint.SetBaselineEntryRequest
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class BaselineAuthoringServiceTest {
    private val blueprintRepository: BlueprintRepository = mockk()
    private val competencyRepository: CompetencyRepository = mockk()

    private val projectId: UUID = UUID.randomUUID()

    private val service = BaselineAuthoringService(blueprintRepository, competencyRepository)

    private fun competency(key: String) = mockk<Competency> {
        every { label } returns key.replaceFirstChar { it.uppercase() }
        every { description } returns null
        every { targetLevel } returns 2
    }

    private fun activeBlueprint() = Blueprint(
        projectId = projectId,
        scope = "global",
        version = "pm-authored",
        status = BlueprintStatus.ACTIVE,
    )

    private fun entry(blueprint: Blueprint, key: String, invariant: Boolean = false) =
        BlueprintCompetency(
            blueprint = blueprint,
            competencyKey = key,
            position = 0,
            status = ProposalStatus.APPROVED,
            invariant = invariant,
        ).also { blueprint.competencies.add(it) }

    @Test
    fun `setEntry creates the project baseline on first write and approves the entry`() {
        every { competencyRepository.findByKey("python") } returns competency("python")
        every {
            blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "global", BlueprintStatus.ACTIVE)
        } returns null
        every { blueprintRepository.save(any()) } answers { firstArg() }

        val response = service.setEntry(projectId, "python", SetBaselineEntryRequest(targetLevel = 3))

        assertThat(response.competencyKey).isEqualTo("python")
        assertThat(response.targetLevel).isEqualTo(3)
        assertThat(response.targetLevelOverridden).isTrue()
        assertThat(response.status).isEqualTo(ProposalStatus.APPROVED)
    }

    @Test
    fun `setEntry updates an existing entry in place rather than duplicating it`() {
        val blueprint = activeBlueprint()
        entry(blueprint, "python")
        every { competencyRepository.findByKey("python") } returns competency("python")
        every {
            blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "global", BlueprintStatus.ACTIVE)
        } returns blueprint
        every { blueprintRepository.save(any()) } answers { firstArg() }

        val response = service.setEntry(projectId, "python", SetBaselineEntryRequest(requirement = "recommended"))

        assertThat(blueprint.competencies).hasSize(1)
        assertThat(response.requirement).isEqualTo("recommended")
    }

    @Test
    fun `setEntry 404s when the competency is not in the graph`() {
        every { competencyRepository.findByKey("ghost") } returns null

        assertThatThrownBy { service.setEntry(projectId, "ghost", SetBaselineEntryRequest()) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `removeEntry refuses to drop a protected mandate`() {
        val blueprint = activeBlueprint()
        entry(blueprint, "security", invariant = true)
        every {
            blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "global", BlueprintStatus.ACTIVE)
        } returns blueprint

        assertThatThrownBy { service.removeEntry(projectId, "security") }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `removeEntry drops a normal entry`() {
        val blueprint = activeBlueprint()
        entry(blueprint, "docker")
        every {
            blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, "global", BlueprintStatus.ACTIVE)
        } returns blueprint
        every { blueprintRepository.save(any()) } answers { firstArg() }

        service.removeEntry(projectId, "docker")

        assertThat(blueprint.competencies).isEmpty()
    }
}
