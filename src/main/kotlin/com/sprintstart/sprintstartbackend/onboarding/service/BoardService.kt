package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardOwner
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ContributionEvidenceKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Board
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BoardCard
import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingTrack
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardCardContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardCardResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardMomentKey
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardMomentResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardPullRequestResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardVocabularyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.OpenPullRequestsContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.PathToFirstContributionContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.metrics.HireTimelineResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BoardCardRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.BoardRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * A hire's board: which cards are on it, and what each one currently says.
 *
 * ### Ensure, then hydrate
 *
 * A read does two things. First it makes sure the cards that are relevant to this hire *exist* —
 * idempotently, and never re-adding one they dismissed. Then it fills each surviving card with a
 * live read.
 *
 * The ensure step runs on every load rather than only at creation because relevance changes: a role
 * moved onto a track that admits pull requests should get the open-work card, and a board seeded
 * once at first read never would. It is the same rule the design states for on-open placement —
 * ensure the state-relevant set exists and is current, never re-add a dismissed card — applied
 * where the board is actually read.
 *
 * ### Hydration reuses the buddy's reads
 *
 * Every card's content comes from the service the equivalent buddy tool reads, so a card and the
 * tool of the same name cannot describe different states. Nothing is copied onto the card row: a
 * second record of facts that already live somewhere durable is the one that goes stale.
 */
@Service
class BoardService(
    private val boardRepository: BoardRepository,
    private val boardCardRepository: BoardCardRepository,
    private val projectMembershipApi: ProjectMembershipApi,
    private val trackService: TrackService,
    private val onboardingMetricsService: OnboardingMetricsService,
    private val openPullRequestReader: OpenPullRequestReader,
) {
    /**
     * This hire's board on this project, cards hydrated.
     *
     * @param userId The hire.
     * @param projectId The project the board belongs to.
     * @return The board, created on first read, or null when this hire is not a member of that
     * project — a board without a membership behind it has nothing to be about.
     */
    @Transactional
    fun getBoard(userId: UUID, projectId: UUID): BoardResponse? {
        val member = memberOrNull(userId, projectId) ?: return null
        val board = boardRepository.findByUserIdAndProjectId(userId, projectId)
            ?: boardRepository.save(Board(userId = userId, projectId = projectId))

        val track = trackService.forMember(member)
        val cards = ensureRelevantCards(board, track)
        val timeline = onboardingMetricsService.getHireTimeline(userId, projectId)

        return BoardResponse(
            boardId = board.id,
            projectId = projectId,
            vocabulary = BoardVocabularyResponse(
                trackLabel = track.label,
                contributionNoun = track.contributionNoun,
                contributionNounPlural = track.contributionNounPlural,
                contributionVerbPast = track.contributionVerbPast,
            ),
            cards = cards
                .filter { it.state == BoardCardState.ACTIVE }
                .sortedBy { it.position }
                .map { card ->
                    BoardCardResponse(
                        id = card.id,
                        kind = card.kind,
                        owner = card.owner,
                        position = card.position,
                        content = hydrate(card.kind, member, projectId, timeline),
                    )
                },
        )
    }

    /**
     * Which cards belong on [board] for this hire, creating any that are missing.
     *
     * Relevance is decided by the hire's track on *this* project, using the per-project
     * [TrackService.forMember] rather than the permissive cross-project read the buddy's tool
     * mounting uses: a board is scoped to one project, so the question "could this person have a
     * pull request here" has a definite answer and there is no reason to guess wider.
     *
     * A card whose kind already has a row is left exactly as it is — dismissed rows included. That
     * is what makes a hire's removal stick.
     *
     * @return Every card row on the board, including ones the hire has dismissed.
     */
    private fun ensureRelevantCards(board: Board, track: OnboardingTrack): List<BoardCard> {
        val existing = boardCardRepository.findAllByBoardId(board.id)
        val present = existing.map { it.kind }.toSet()
        val missing = relevantKinds(track).filterNot { it in present }
        if (missing.isEmpty()) return existing

        // New cards go after everything already there, so ensuring a card exists never reshuffles a
        // board the hire has arranged.
        var nextPosition = (existing.maxOfOrNull { it.position } ?: -1) + 1
        val added = missing.map { kind ->
            BoardCard(
                boardId = board.id,
                kind = kind,
                // Placed for the hire rather than by them: they may dismiss it, they do not edit it.
                owner = BoardCardOwner.AI,
                position = nextPosition++,
            )
        }
        return existing + boardCardRepository.saveAll(added)
    }

    /**
     * The card kinds worth showing this hire, in the order they are first placed.
     *
     * The path card is universal — its timeline is composed from contributions, so it says
     * something true whatever produces this hire's work. The open-pull-request card is gated on the
     * track admitting pull requests, exactly where the buddy's `get_my_open_pull_requests` tool is
     * gated and for the same reason: a permanently empty "your open pull requests" card in front of
     * somebody who will never have one is the invisible-hire problem in card form.
     */
    private fun relevantKinds(track: OnboardingTrack): List<BoardCardKind> = buildList {
        add(BoardCardKind.PATH_TO_FIRST_CONTRIBUTION)
        if (track.admits(ContributionEvidenceKind.PULL_REQUEST)) {
            add(BoardCardKind.OPEN_PULL_REQUESTS)
        }
    }

    private fun hydrate(
        kind: BoardCardKind,
        member: ProjectMember,
        projectId: UUID,
        timeline: HireTimelineResponse?,
    ): BoardCardContent = when (kind) {
        BoardCardKind.PATH_TO_FIRST_CONTRIBUTION -> pathContent(member, timeline)
        BoardCardKind.OPEN_PULL_REQUESTS -> openPullRequestsContent(member, projectId)
    }

    /**
     * The path card's content, from the same timeline the PM dashboard reads.
     *
     * A hire with no timeline at all still gets the card, with every moment unreached: "nothing has
     * happened yet" is the honest day-one state and is exactly what somebody on day one should see,
     * rather than a card that is missing until they have already made progress.
     */
    private fun pathContent(
        member: ProjectMember,
        timeline: HireTimelineResponse?,
    ): PathToFirstContributionContent = PathToFirstContributionContent(
        moments = listOf(
            // Joined comes from the membership rather than the timeline, so it is still shown when
            // there is no timeline to read.
            BoardMomentResponse(BoardMomentKey.JOINED, member.joinedAt),
            BoardMomentResponse(BoardMomentKey.TASK_CLAIMED, timeline?.firstTaskClaimedAt),
            // The timeline's field names still say "pull request"; the values behind them are
            // composed from contributions of any kind, which is why the card can name them
            // generally.
            BoardMomentResponse(BoardMomentKey.WORK_SUBMITTED, timeline?.firstPullRequestOpenedAt),
            BoardMomentResponse(BoardMomentKey.FIRST_RESPONSE, timeline?.firstResponseAt),
            BoardMomentResponse(BoardMomentKey.WORK_ACCEPTED, timeline?.firstPullRequestMergedAt),
        ),
        acceptedCount = timeline?.mergedPullRequestCount ?: 0,
        autonomyReachedAt = timeline?.autonomyReachedAt,
        stalledReason = timeline?.stalledReason,
    )

    private fun openPullRequestsContent(
        member: ProjectMember,
        projectId: UUID,
    ): OpenPullRequestsContent {
        val login = member.githubLogin
        val open = openPullRequestReader.openFor(projectId, login)
        return OpenPullRequestsContent(
            pullRequests = open.map { pullRequest ->
                BoardPullRequestResponse(
                    artifactId = pullRequest.artifactId,
                    number = pullRequest.number,
                    title = pullRequest.title,
                    url = pullRequest.sourceUrl,
                    waitingHours = openPullRequestReader.waitingHours(pullRequest),
                )
            },
            attributionMissing = login.isNullOrBlank(),
        )
    }

    private fun memberOrNull(userId: UUID, projectId: UUID): ProjectMember? =
        projectMembershipApi.getProjectMembers(projectId).firstOrNull { it.userId == userId }
}
