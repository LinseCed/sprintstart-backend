package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingTrack
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.RungState
import com.sprintstart.sprintstartbackend.onboarding.model.response.setup.SetupReadinessResponse
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
    private val starterWork: StarterWorkTaskProposalRepository = mockk()
    private val membership: ProjectMembershipApi = mockk()

    // No track beyond the default is in use by default, so an uncovered-track warning cannot fire
    // in the existing cases; coverage has its own tests below.
    private val tracks: TrackService = mockk {
        every { tracksInUse() } returns emptyList()
    }

    private val projectId: UUID = UUID.randomUUID()

    private val service = SetupReadinessService(
        graphAuthoring,
        competencyProposals,
        edgeProposals,
        starterWork,
        membership,
        tracks,
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

    // Real rows rather than bare mocks: the rung now reads each task's track, and a mock would
    // answer that with whatever mockk invents rather than with the coverage the test describes.
    private fun task(trackKey: String? = null) = StarterWorkTaskProposal(
        sourceId = "src-${UUID.randomUUID()}",
        title = "A starter task",
        onboardingTrackKey = trackKey,
    )

    private fun starterTasks(approved: Int, pending: Int, trackKey: String? = null) {
        every { starterWork.findAllByStatus(ProposalStatus.APPROVED) } returns
            List(approved) { task(trackKey) }
        every { starterWork.findAllByStatus(ProposalStatus.PROPOSED) } returns
            List(pending) { task() }
    }

    /** The bug that motivated this: proposals generated, none approved -> a page read "empty". */
    @Test
    fun `pending proposals surface as a review warning rather than as nothing`() {
        approvedCompetencies(0)
        pendingGraph(nodes = 25, edges = 19)
        starterTasks(approved = 0, pending = 0)
        every { membership.getProjectMembers(projectId) } returns emptyList()

        val response = service.getReadiness(projectId)

        val skillMap = rungOf(response, "skill-map")
        assertThat(skillMap.state).isEqualTo(RungState.WARN)
        assertThat(skillMap.detail).contains("25 competencies", "19 edges", "waiting for your review")
        assertThat(response.ready).isFalse()
    }

    @Test
    fun `a fully set up project reads ready`() {
        approvedCompetencies(6)
        pendingGraph(nodes = 0, edges = 0)
        starterTasks(approved = 2, pending = 0)

        val response = service.getReadiness(projectId)

        assertThat(response.rungs.map { it.state }).containsOnly(RungState.OK)
        assertThat(response.ready).isTrue()
        // Two rungs have gone, each when the work behind it stopped existing: the human-loop rung
        // with the buddy loop, and the baseline rung when the path became goal-directed and nothing
        // read the selection any more.
        assertThat(response.rungs.map { it.key }).containsExactly("skill-map", "starter-tasks")
    }

    @Test
    fun `starter work covering no track a role is on is a warning, not readiness`() {
        approvedCompetencies(4)
        pendingGraph(nodes = 0, edges = 0)
        // Every approved task is engineering work; somebody is onboarding on delivery.
        starterTasks(approved = 3, pending = 0, trackKey = "engineering")
        every { tracks.tracksInUse() } returns listOf(
            OnboardingTrack(
                key = "delivery",
                label = "Agile delivery",
                contributionNoun = "ceremony",
                contributionNounPlural = "ceremonies",
                contributionVerbPast = "facilitated",
            ),
        )

        val rung = rungOf(service.getReadiness(projectId), "starter-tasks")

        // The failure this catches: plenty of tasks, none a delivery lead could pick up, on a
        // project the ladder would otherwise call ready.
        assertThat(rung.state).isEqualTo(RungState.WARN)
        assertThat(rung.detail).contains("Agile delivery")
    }

    @Test
    fun `an unscoped task counts as coverage for every track`() {
        approvedCompetencies(4)
        pendingGraph(nodes = 0, edges = 0)
        // Null means "suits any role", so it is coverage for everybody rather than for nobody.
        starterTasks(approved = 2, pending = 0, trackKey = null)
        every { tracks.tracksInUse() } returns listOf(
            OnboardingTrack(
                key = "delivery",
                label = "Agile delivery",
                contributionNoun = "ceremony",
                contributionNounPlural = "ceremonies",
                contributionVerbPast = "facilitated",
            ),
        )

        assertThat(rungOf(service.getReadiness(projectId), "starter-tasks").state).isEqualTo(RungState.OK)
    }
}
