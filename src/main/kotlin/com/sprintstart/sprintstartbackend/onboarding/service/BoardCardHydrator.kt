package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.Rigor
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BoardCard
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BoardDiagram
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toLastChangeResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.arrival.ArrivalStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.ArrivalStepsContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardCardContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardCardResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardPullRequestResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.CompetencyProgressContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.CurrentTaskContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.MemoryRecapContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.board.OpenPullRequestsContent
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.MyCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.RankedStarterWorkTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.BuddySessionRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMember
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What each card on a hire's board currently says.
 *
 * Each card's content is read from the same service the equivalent buddy tool reads. Nothing is
 * copied onto the card row, so a card always says what its source says now.
 */
@Component
class BoardCardHydrator(
    private val openPullRequestReader: OpenPullRequestReader,
    private val currentTaskReader: CurrentTaskReader,
    private val starterWorkTaskProposalService: StarterWorkTaskProposalService,
    private val myCompetencyService: MyCompetencyService,
    private val buddySessionRepository: BuddySessionRepository,
    private val boardDiagramService: BoardDiagramService,
) {
    /**
     * One card as the board shows it, its content read live.
     *
     * A whole board passes what it read once for every card — the diagrams, the hire's arrival
     * steps and path steps, one starter-work ranking — so the board pays for each read once; a
     * single card rendered on its own reads what it needs.
     */
    fun render(
        card: BoardCard,
        member: ProjectMember,
        projectId: UUID,
        diagram: BoardDiagram? = null,
        arrivalSteps: List<ResolvedArrivalStep> = emptyList(),
        pathSteps: Map<UUID, ResolvedPathStep> = emptyMap(),
        // A single card rendered on its own ranks for itself; a whole board shares one ranking.
        matches: () -> List<RankedStarterWorkTaskResponse> = {
            starterWorkTaskProposalService.matchForUserId(member.userId, projectId)
        },
    ): BoardCardResponse = BoardCardResponse(
        id = card.id,
        kind = card.kind,
        owner = card.owner,
        position = card.position,
        placedAt = card.placedAt,
        content = hydrate(card, member, projectId, diagram, arrivalSteps, pathSteps, matches),
        lastChange = card.toLastChangeResponse(),
    )

    private fun hydrate(
        card: BoardCard,
        member: ProjectMember,
        projectId: UUID,
        diagram: BoardDiagram?,
        arrivalSteps: List<ResolvedArrivalStep>,
        pathSteps: Map<UUID, ResolvedPathStep>,
        matches: () -> List<RankedStarterWorkTaskResponse>,
    ): BoardCardContent = when (card.kind) {
        BoardCardKind.ARRIVAL_STEPS -> arrivalStepsContent(arrivalSteps)
        BoardCardKind.OPEN_PULL_REQUESTS -> openPullRequestsContent(member, projectId)
        BoardCardKind.CURRENT_TASK -> currentTaskContent(member.userId, projectId)
        BoardCardKind.SUGGESTED_TASKS -> BoardTaskCards.suggested(matches())
        BoardCardKind.TASK_POOL -> BoardTaskCards.pool(
            matches(),
            currentTaskId = currentTaskReader.currentTaskFor(member.userId, projectId)?.id,
        )
        BoardCardKind.COMPETENCY_PROGRESS -> competencyProgressContent(member.userId)
        BoardCardKind.MEMORY_RECAP -> memoryRecapContent(member.userId)
        // The one card served from a cache: its content costs a model call.
        // [BoardDiagramService] owns whether that cache is still valid.
        BoardCardKind.DIAGRAM -> boardDiagramService.contentFor(card.subject.orEmpty(), diagram)
        BoardCardKind.PATH_STEP -> pathStepContent(card.subject, pathSteps)
        BoardCardKind.NOTE, BoardCardKind.LINK, BoardCardKind.CHECKLIST -> authoredContent(card.payload)
    }

    /**
     * What is still outstanding before this hire can work, counted by how each step was settled.
     *
     * The same read the hire's own `GET /me/arrival` serves, so the card and that endpoint cannot
     * disagree — the rule every other card here follows.
     *
     * Counted per rigor and never totalled. A step the system observed and a step somebody
     * ticked are different facts, and a single blended figure here would be meaningless.
     */
    private fun arrivalStepsContent(steps: List<ResolvedArrivalStep>): ArrivalStepsContent {
        val responses: List<ArrivalStepResponse> = steps.map { it.toResponse() }

        return ArrivalStepsContent(
            steps = responses,
            observedCount = responses.count { it.rigor == Rigor.OBSERVED },
            declaredCount = responses.count { it.rigor == Rigor.DECLARED },
            outstandingCount = responses.count { !it.settled },
        )
    }

    /**
     * The hire's ledger, split at the bar rather than summed into a percentage.
     *
     * The same read and the same level-0 exclusion as the buddy's `get_my_competencies` tool.
     * Level 0 means "asked, saw no evidence" — a placement, not a competency — so it is
     * filtered out here. The ledger is global, not per project.
     */
    private fun competencyProgressContent(userId: UUID): CompetencyProgressContent {
        val (held, inProgress) = myCompetencyService
            .getCompetenciesForUser(userId)
            .filter { it.level > 0 }
            .partition { it.level >= it.targetLevel }
        return CompetencyProgressContent(
            held = held.map { it.toBoardResponse() },
            inProgress = inProgress.map { it.toBoardResponse() },
        )
    }

    private fun MyCompetencyResponse.toBoardResponse() = BoardCompetencyResponse(
        competencyKey = competencyKey,
        label = label,
        level = level,
        targetLevel = targetLevel,
    )

    /**
     * What the mentor remembers, read and never written.
     *
     * Not [BuddyService.getOrCreateSession] — hydrating a card must not create a session.
     */
    private fun memoryRecapContent(userId: UUID): MemoryRecapContent {
        val session = buddySessionRepository
            .findByUserIdOrderByCreatedAtDesc(userId)
            .firstOrNull()

        return MemoryRecapContent(
            memory = session?.summary,
            messagesRemembered = session?.summarizedCount ?: 0,
        )
    }

    /**
     * The task the hire is on, read — never assigned.
     *
     * Read through [CurrentTaskReader], the same read the task packet uses, so the card and the
     * packet cannot be about different tasks.
     *
     * A card with no task on it is a real state and says so.
     */
    private fun currentTaskContent(userId: UUID, projectId: UUID): CurrentTaskContent {
        val task = currentTaskReader.currentTaskFor(userId, projectId)
        return CurrentTaskContent(
            taskId = task?.id,
            title = task?.title,
            summary = task?.summary,
            url = task?.sourceUrl,
            // Reconciliation moves a proposal to STALE when its issue closes at the source, so the
            // card can say so without a lookup of its own.
            closedAtSource = task?.status == ProposalStatus.STALE,
        )
    }

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
}
