package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.ingestion.external.AuthoredPullRequest
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardOwner
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContributionEvidenceKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Board
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BoardCard
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingTrack
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardMomentKey
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardMomentResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.OpenPullRequestsContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.PathToFirstContributionContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.HireTimelineResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BoardCardRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BoardRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardServiceTest {
    private val boardRepository: BoardRepository = mockk()
    private val boardCardRepository: BoardCardRepository = mockk()
    private val projectMembershipApi: ProjectMembershipApi = mockk()
    private val trackService: TrackService = mockk()
    private val onboardingMetricsService: OnboardingMetricsService = mockk()
    private val artifactIngestionApi: ArtifactIngestionApi = mockk()

    private val now: Instant = Instant.parse("2026-07-27T12:00:00Z")
    private val hireId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()

    private val service = BoardService(
        boardRepository,
        boardCardRepository,
        projectMembershipApi,
        trackService,
        onboardingMetricsService,
        OpenPullRequestReader(artifactIngestionApi, Clock.fixed(now, ZoneOffset.UTC)),
    )

    private val engineering = OnboardingTrack(
        key = OnboardingTrack.DEFAULT_KEY,
        label = "Engineering",
        contributionNoun = "change",
        contributionNounPlural = "changes",
        contributionVerbPast = "merged",
        evidenceKinds = mutableSetOf(ContributionEvidenceKind.PULL_REQUEST),
    )

    private val scrumMaster = OnboardingTrack(
        key = "scrum-master",
        label = "Scrum Master",
        contributionNoun = "ceremony",
        contributionNounPlural = "ceremonies",
        contributionVerbPast = "facilitated",
        evidenceKinds = mutableSetOf(ContributionEvidenceKind.ATTESTATION),
    )

    @BeforeEach
    fun setUp() {
        every { projectMembershipApi.getProjectMembers(projectId) } returns listOf(member())
        every { trackService.forMember(any()) } returns engineering
        every { onboardingMetricsService.getHireTimeline(hireId, projectId) } returns timeline()
        every { artifactIngestionApi.getAuthoredPullRequests(projectId, "ada") } returns emptyList()
        every { boardRepository.save(any()) } answers { firstArg() }
        every { boardCardRepository.saveAll(any<List<BoardCard>>()) } answers { firstArg() }
        every { boardCardRepository.findAllByBoardId(any()) } returns emptyList()
    }

    private fun member(
        githubLogin: String? = "ada",
        joinedAt: Instant? = now.minusSeconds(86_400),
    ) = ProjectMember(
        userId = hireId,
        displayName = "Ada",
        githubLogin = githubLogin,
        joinedAt = joinedAt,
    )

    @Suppress("LongParameterList")
    private fun timeline(
        firstTaskClaimedAt: Instant? = null,
        firstOpenedAt: Instant? = null,
        firstResponseAt: Instant? = null,
        acceptedAt: Instant? = null,
        acceptedCount: Int = 0,
        stalledReason: String? = null,
        autonomyReachedAt: Instant? = null,
    ) = HireTimelineResponse(
        userId = hireId,
        displayName = "Ada",
        githubLogin = "ada",
        joinedAt = now.minusSeconds(86_400),
        taskZeroAssignedAt = null,
        firstTaskClaimedAt = firstTaskClaimedAt,
        firstPullRequestOpenedAt = firstOpenedAt,
        firstResponseAt = firstResponseAt,
        firstPullRequestMergedAt = acceptedAt,
        hoursToFirstMergedPullRequest = null,
        hoursToFirstResponse = null,
        mergedPullRequestCount = acceptedCount,
        openPullRequestCount = 0,
        longestOpenWaitHours = null,
        stalled = stalledReason != null,
        stalledReason = stalledReason,
        autonomyReachedAt = autonomyReachedAt,
        reworkedPullRequestCount = 0,
    )

    private fun existingBoard(): Board {
        val board = Board(userId = hireId, projectId = projectId)
        every { boardRepository.findByUserIdAndProjectId(hireId, projectId) } returns board
        return board
    }

    private fun noBoardYet() {
        every { boardRepository.findByUserIdAndProjectId(hireId, projectId) } returns null
    }

    @Test
    fun `creates the board on first read`() {
        noBoardYet()

        val board = service.getBoard(hireId, projectId)

        assertNotNull(board)
        verify { boardRepository.save(any()) }
    }

    @Test
    fun `a hire who is not a member of the project has no board`() {
        every { projectMembershipApi.getProjectMembers(projectId) } returns emptyList()

        assertNull(service.getBoard(hireId, projectId))
    }

    @Test
    fun `an engineering hire gets the path card and the open pull request card`() {
        noBoardYet()

        val kinds = service.getBoard(hireId, projectId)?.cards?.map { it.kind }

        assertEquals(
            listOf(BoardCardKind.PATH_TO_FIRST_CONTRIBUTION, BoardCardKind.OPEN_PULL_REQUESTS),
            kinds,
        )
    }

    @Test
    fun `a track that cannot have pull requests is not given a pull request card`() {
        noBoardYet()
        every { trackService.forMember(any()) } returns scrumMaster

        val kinds = service.getBoard(hireId, projectId)?.cards?.map { it.kind }

        // Absent, not empty: a permanently empty "your open pull requests" card in front of
        // somebody who will never have one is exactly the wrong opening.
        assertEquals(listOf(BoardCardKind.PATH_TO_FIRST_CONTRIBUTION), kinds)
    }

    @Test
    fun `the path card is placed for every track, because its moments are not about git`() {
        noBoardYet()
        every { trackService.forMember(any()) } returns scrumMaster

        val board = service.getBoard(hireId, projectId)

        assertTrue(
            board?.cards.orEmpty().any { it.kind == BoardCardKind.PATH_TO_FIRST_CONTRIBUTION },
        )
    }

    @Test
    fun `the board carries the track's own words`() {
        noBoardYet()
        every { trackService.forMember(any()) } returns scrumMaster

        val vocabulary = service.getBoard(hireId, projectId)?.vocabulary

        assertEquals("ceremony", vocabulary?.contributionNoun)
        assertEquals("ceremonies", vocabulary?.contributionNounPlural)
        assertEquals("facilitated", vocabulary?.contributionVerbPast)
        assertEquals("Scrum Master", vocabulary?.trackLabel)
    }

    @Test
    fun `ensuring cards is idempotent — a second read adds nothing`() {
        val board = existingBoard()
        every { boardCardRepository.findAllByBoardId(board.id) } returns listOf(
            BoardCard(
                boardId = board.id,
                kind = BoardCardKind.PATH_TO_FIRST_CONTRIBUTION,
                owner = BoardCardOwner.AI,
                position = 0,
            ),
            BoardCard(
                boardId = board.id,
                kind = BoardCardKind.OPEN_PULL_REQUESTS,
                owner = BoardCardOwner.AI,
                position = 1,
            ),
        )

        service.getBoard(hireId, projectId)

        verify(exactly = 0) { boardCardRepository.saveAll(any<List<BoardCard>>()) }
    }

    @Test
    fun `a dismissed card is not put back, and is not shown`() {
        val board = existingBoard()
        every { boardCardRepository.findAllByBoardId(board.id) } returns listOf(
            BoardCard(
                boardId = board.id,
                kind = BoardCardKind.OPEN_PULL_REQUESTS,
                owner = BoardCardOwner.AI,
                state = BoardCardState.DISMISSED,
                position = 0,
            ),
        )

        val kinds = service.getBoard(hireId, projectId)?.cards?.map { it.kind }

        // The dismissed row is what makes the removal stick: the path card is added because it is
        // missing, the pull-request card is not re-added because the hire said no to it.
        assertEquals(listOf(BoardCardKind.PATH_TO_FIRST_CONTRIBUTION), kinds)
    }

    @Test
    fun `a newly relevant card is added after the cards already on the board`() {
        val board = existingBoard()
        every { boardCardRepository.findAllByBoardId(board.id) } returns listOf(
            BoardCard(
                boardId = board.id,
                kind = BoardCardKind.PATH_TO_FIRST_CONTRIBUTION,
                owner = BoardCardOwner.AI,
                position = 7,
            ),
        )

        val cards = service.getBoard(hireId, projectId)?.cards.orEmpty()

        // Ensuring a card exists must never reshuffle a board the hire has arranged.
        assertEquals(BoardCardKind.OPEN_PULL_REQUESTS, cards.last().kind)
        assertEquals(8, cards.last().position)
    }

    @Test
    fun `an unreached moment is absent, never zero`() {
        noBoardYet()

        val content = service.pathCard()

        assertEquals(now.minusSeconds(86_400), content.moments.momentAt(BoardMomentKey.JOINED))
        assertNull(content.moments.momentAt(BoardMomentKey.WORK_ACCEPTED))
        assertEquals(0, content.acceptedCount)
    }

    @Test
    fun `the path card reports the moments the timeline has reached`() {
        noBoardYet()
        val accepted = now.minusSeconds(3_600)
        every { onboardingMetricsService.getHireTimeline(hireId, projectId) } returns timeline(
            firstTaskClaimedAt = now.minusSeconds(50_000),
            firstOpenedAt = now.minusSeconds(20_000),
            firstResponseAt = now.minusSeconds(10_000),
            acceptedAt = accepted,
            acceptedCount = 2,
            autonomyReachedAt = accepted,
        )

        val content = service.pathCard()

        assertEquals(accepted, content.moments.momentAt(BoardMomentKey.WORK_ACCEPTED))
        assertEquals(2, content.acceptedCount)
        assertEquals(accepted, content.autonomyReachedAt)
    }

    @Test
    fun `a hire with no timeline still gets the card, with nothing reached`() {
        noBoardYet()
        every { onboardingMetricsService.getHireTimeline(hireId, projectId) } returns null

        val content = service.pathCard()

        // Day one is a real state, and the card that describes it must exist on day one.
        assertEquals(now.minusSeconds(86_400), content.moments.momentAt(BoardMomentKey.JOINED))
        assertTrue(content.moments.drop(1).all { it.reachedAt == null })
    }

    @Test
    fun `a stall is shown to the person in it`() {
        noBoardYet()
        every { onboardingMetricsService.getHireTimeline(hireId, projectId) } returns
            timeline(stalledReason = "no response in 5 days")

        assertEquals("no response in 5 days", service.pathCard().stalledReason)
    }

    @Test
    fun `open pull requests are listed longest-waiting first, with the answered one not waiting`() {
        noBoardYet()
        every { artifactIngestionApi.getAuthoredPullRequests(projectId, "ada") } returns listOf(
            openPullRequest(number = 2, openedAt = now.minusSeconds(3_600)),
            openPullRequest(
                number = 3,
                openedAt = now.minusSeconds(360_000),
                firstResponseAt = now.minusSeconds(1_000),
            ),
            openPullRequest(number = 1, openedAt = now.minusSeconds(72_000)),
        )

        val content = service.pullRequestCard()

        assertEquals(listOf(3, 1, 2), content.pullRequests.map { it.number })
        // Answered: the clock the hire cares about has stopped, so it is not "waiting" at all.
        assertNull(content.pullRequests.first { it.number == 3 }.waitingHours)
        assertEquals(20, content.pullRequests.first { it.number == 1 }.waitingHours)
        assertFalse(content.attributionMissing)
    }

    @Test
    fun `a pull request closed without merging is not open`() {
        noBoardYet()
        every { artifactIngestionApi.getAuthoredPullRequests(projectId, "ada") } returns listOf(
            openPullRequest(number = 4, openedAt = now.minusSeconds(3_600), state = "CLOSED"),
        )

        assertTrue(service.pullRequestCard().pullRequests.isEmpty())
    }

    @Test
    fun `no declared GitHub login reads as unattributable, not as nothing open`() {
        noBoardYet()
        every { projectMembershipApi.getProjectMembers(projectId) } returns
            listOf(member(githubLogin = null))

        val content = service.pullRequestCard()

        assertTrue(content.pullRequests.isEmpty())
        assertTrue(content.attributionMissing)
    }

    private fun openPullRequest(
        number: Int,
        openedAt: Instant,
        firstResponseAt: Instant? = null,
        state: String? = "OPEN",
    ) = AuthoredPullRequest(
        artifactId = UUID.randomUUID(),
        openedAt = openedAt,
        firstResponseAt = firstResponseAt,
        mergedAt = null,
        state = state,
        number = number,
        title = "PR $number",
        sourceUrl = "https://example.test/pr/$number",
    )

    private fun BoardService.pathCard(): PathToFirstContributionContent =
        getBoard(hireId, projectId)!!
            .cards
            .first { it.kind == BoardCardKind.PATH_TO_FIRST_CONTRIBUTION }
            .content as PathToFirstContributionContent

    private fun BoardService.pullRequestCard(): OpenPullRequestsContent =
        getBoard(hireId, projectId)!!
            .cards
            .first { it.kind == BoardCardKind.OPEN_PULL_REQUESTS }
            .content as OpenPullRequestsContent

    private fun List<BoardMomentResponse>.momentAt(key: BoardMomentKey): Instant? =
        first { it.key == key }.reachedAt
}
