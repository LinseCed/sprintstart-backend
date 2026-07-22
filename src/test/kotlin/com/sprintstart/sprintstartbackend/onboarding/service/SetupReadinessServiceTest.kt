package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Blueprint
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.RungState
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.SetupReadinessResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SetupReadinessServiceTest {
    private val graphAuthoring: CompetencyGraphAuthoringService = mockk()
    private val competencyProposals: CompetencyProposalRepository = mockk()
    private val edgeProposals: CompetencyEdgeProposalRepository = mockk()
    private val blueprints: BlueprintRepository = mockk()
    private val starterWork: StarterWorkTaskProposalRepository = mockk()
    private val membership: ProjectMembershipApi = mockk()

    private val projectId: UUID = UUID.randomUUID()

    private val service = SetupReadinessService(
        graphAuthoring,
        competencyProposals,
        edgeProposals,
        blueprints,
        starterWork,
        membership,
    )

    private fun rungOf(response: SetupReadinessResponse, key: String) =
        response.rungs.single { it.key == key }

    /** Only sizes/fields the service reads matter, so entity instances are relaxed mocks. */
    private fun approvedCompetencies(n: Int) {
        every { graphAuthoring.getGraph() } returns
            CompetencyGraphResponse(
                competencies = List(n) { mockk() },
                edges = emptyList(),
                graphVersion = 1,
            )
    }

    private fun pendingGraph(nodes: Int, edges: Int) {
        every { competencyProposals.findAllByStatus(ProposalStatus.PROPOSED) } returns
            List(nodes) { mockk<CompetencyProposal>() }
        every { edgeProposals.findAllByStatus(ProposalStatus.PROPOSED) } returns
            List(edges) { mockk<CompetencyEdgeProposal>() }
    }

    private fun activeBaseline(approvedEntries: Int) {
        val blueprint = mockk<Blueprint>()
        every { blueprint.projectId } returns projectId
        every { blueprint.competencies } returns
            List(approvedEntries) {
                mockk<BlueprintCompetency> { every { status } returns ProposalStatus.APPROVED }
            }.toMutableList()
        every { blueprints.findAllByStatus(BlueprintStatus.ACTIVE) } returns listOf(blueprint)
    }

    private fun starterTasks(approved: Int, pending: Int) {
        every { starterWork.findAllByStatus(ProposalStatus.APPROVED) } returns
            List(approved) { mockk<StarterWorkTaskProposal>() }
        every { starterWork.findAllByStatus(ProposalStatus.PROPOSED) } returns
            List(pending) { mockk<StarterWorkTaskProposal>() }
    }

    /** The bug that motivated this: proposals generated, none approved -> the baseline read "empty". */
    @Test
    fun `pending proposals surface as a review warning and block the baseline`() {
        approvedCompetencies(0)
        pendingGraph(nodes = 25, edges = 19)
        every { blueprints.findAllByStatus(BlueprintStatus.ACTIVE) } returns emptyList()
        starterTasks(approved = 0, pending = 0)
        every { membership.getProjectMembers(projectId) } returns emptyList()

        val response = service.getReadiness(projectId)

        val skillMap = rungOf(response, "skill-map")
        assertThat(skillMap.state).isEqualTo(RungState.WARN)
        assertThat(skillMap.detail).contains("25 competencies", "19 edges", "waiting for your review")

        val baseline = rungOf(response, "baseline")
        assertThat(baseline.state).isEqualTo(RungState.BLOCKED)
        assertThat(response.ready).isFalse()
    }

    @Test
    fun `a fully set up project reads ready`() {
        approvedCompetencies(6)
        pendingGraph(nodes = 0, edges = 0)
        activeBaseline(approvedEntries = 3)
        starterTasks(approved = 2, pending = 0)

        val response = service.getReadiness(projectId)

        assertThat(response.rungs.map { it.state }).containsOnly(RungState.OK)
        assertThat(response.ready).isTrue()
        // The human-loop rung is gone with the buddy loop itself: three onboarding stages remain.
        assertThat(response.rungs.map { it.key }).containsExactly("skill-map", "baseline", "starter-tasks")
    }
}
