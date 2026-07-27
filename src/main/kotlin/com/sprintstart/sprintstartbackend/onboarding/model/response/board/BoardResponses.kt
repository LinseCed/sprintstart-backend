package com.sprintstart.sprintstartbackend.onboarding.model.response.board

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardOwner
import java.time.Instant
import java.util.UUID

/**
 * A hire's board on one project: the cards on it, and the words to describe their work in.
 *
 * [vocabulary] rides the board rather than each card because it is the same on all of them, and
 * because the alternative — every card kind inventing its own noun — is how a board ends up telling
 * a Scrum Master about their pull requests in one panel and their ceremonies in the next.
 */
data class BoardResponse(
    val boardId: UUID,
    val projectId: UUID,
    val vocabulary: BoardVocabularyResponse,
    /** Active cards only, in board order. A dismissed card is gone from the hire's point of view. */
    val cards: List<BoardCardResponse>,
)

/**
 * How this hire's accepted work is named, taken from their track.
 *
 * Sent to the client rather than baked into server-side copy because the board renders sentences
 * around live numbers ("2 changes merged", "1 ceremony facilitated") and the client is what builds
 * them. Structured fields, never prose: a track supplies nouns to fixed slots, it does not get to
 * write the sentence.
 */
data class BoardVocabularyResponse(
    /** The track's own name, e.g. "Engineering" — for saying whose board this is set up as. */
    val trackLabel: String,
    /** One unit of accepted work, bare: "change", "ceremony". */
    val contributionNoun: String,
    val contributionNounPlural: String,
    /** The hire's own act in the past tense: "merged", "facilitated". */
    val contributionVerbPast: String,
)

/**
 * One card, with the content it renders.
 *
 * [content] is polymorphic on [BoardCardKind] rather than a bag of optional fields, so a client
 * that handles a kind gets exactly the data that kind has and a client that does not can still see
 * what it is looking at. The catalog is closed, so this union is complete by construction.
 */
data class BoardCardResponse(
    val id: UUID,
    val kind: BoardCardKind,
    val owner: BoardCardOwner,
    val position: Int,
    /**
     * When the mentor put this card here; null when the board keeps it as part of the baseline.
     *
     * The client says "your buddy added this" only for a card that has one. Attribution the hire
     * cannot check is attribution they cannot trust, so the board never claims a placement it did
     * not make.
     */
    val placedAt: Instant?,
    val content: BoardCardContent,
)

/**
 * The rendered content of one card.
 *
 * Every implementation is a live read composed at request time. None of them carry a copy of
 * anything stored on the card row, which is what guarantees a card and the buddy tool behind it
 * cannot disagree.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind",
    visible = true,
)
@JsonSubTypes(
    JsonSubTypes.Type(
        value = PathToFirstContributionContent::class,
        name = "PATH_TO_FIRST_CONTRIBUTION",
    ),
    JsonSubTypes.Type(value = OpenPullRequestsContent::class, name = "OPEN_PULL_REQUESTS"),
    JsonSubTypes.Type(value = CurrentTaskContent::class, name = "CURRENT_TASK"),
    JsonSubTypes.Type(value = SuggestedTasksContent::class, name = "SUGGESTED_TASKS"),
)
sealed interface BoardCardContent {
    val kind: BoardCardKind
}

/**
 * The moments between joining and a first accepted piece of work.
 *
 * Composed from the hire's contribution timeline, so it is true for every track rather than only
 * for the ones that produce pull requests. Every timestamp is nullable because "has not happened
 * yet" is the normal state of somebody mid-onboarding, and it is a different thing from zero.
 */
data class PathToFirstContributionContent(
    override val kind: BoardCardKind = BoardCardKind.PATH_TO_FIRST_CONTRIBUTION,
    val moments: List<BoardMomentResponse>,
    /** How much accepted work there is so far — the ramp's only real counter. */
    val acceptedCount: Int,
    /** When onboarding ended for this hire, dated. Null while it is still going. */
    val autonomyReachedAt: Instant?,
    /**
     * Why this hire currently reads as stalled, in plain words, or null when they do not.
     *
     * Shown to the hire rather than only to the PM: a stall the person in it cannot see is a stall
     * only somebody else can fix.
     */
    val stalledReason: String?,
) : BoardCardContent

/**
 * One moment on the path, and whether it has happened.
 *
 * [key] is a stable identifier the client maps to its own copy; [reachedAt] null means not yet, and
 * the client renders that as a dash rather than as a zero.
 */
data class BoardMomentResponse(
    val key: BoardMomentKey,
    val reachedAt: Instant?,
)

/** The moments a path card reports, in the order they normally happen. */
enum class BoardMomentKey {
    JOINED,
    TASK_CLAIMED,
    WORK_SUBMITTED,
    FIRST_RESPONSE,
    WORK_ACCEPTED,
}

/**
 * The hire's still-open pull requests, longest-waiting first.
 *
 * Only present on a board whose track admits pull requests — see [BoardCardKind.OPEN_PULL_REQUESTS]
 * for why an empty one is worse than no card at all.
 */
data class OpenPullRequestsContent(
    override val kind: BoardCardKind = BoardCardKind.OPEN_PULL_REQUESTS,
    val pullRequests: List<BoardPullRequestResponse>,
    /**
     * True when the hire has declared no GitHub login, so nothing can be attributed to them.
     *
     * Distinct from an empty list on purpose: "you have nothing open" and "I cannot tell what you
     * have open" are different states, and only one of them is the hire's to fix.
     */
    val attributionMissing: Boolean,
) : BoardCardContent

/**
 * One open pull request.
 *
 * [waitingHours] is null once somebody has responded — the clock the hire cares about has stopped,
 * and reporting elapsed time as a wait would be a complaint about a review that already happened.
 */
data class BoardPullRequestResponse(
    val artifactId: UUID,
    val number: Int?,
    val title: String?,
    val url: String?,
    val waitingHours: Long?,
)

/**
 * The task the hire is on, or the fact that they are on none.
 *
 * Present-but-empty rather than absent when there is no task: a card that vanishes when a goal is
 * cleared reads as the board losing something, and "you have no task right now" is a state worth
 * being told about — it is usually the thing to fix.
 */
data class CurrentTaskContent(
    override val kind: BoardCardKind = BoardCardKind.CURRENT_TASK,
    val taskId: UUID?,
    val title: String?,
    val summary: String?,
    val url: String?,
    /**
     * True when the hire claimed this as their goal, false when it is the Task 0 they were handed.
     *
     * Worth distinguishing because only one of the two is theirs to change their mind about.
     */
    val chosen: Boolean,
) : BoardCardContent

/**
 * Good next tasks, ranked by fit.
 *
 * Carries [BoardSuggestedTaskResponse.reasons] and deliberately no score. The ranker was built to
 * explain itself in one line per signal, and a number is not a reason anybody can act on.
 */
data class SuggestedTasksContent(
    override val kind: BoardCardKind = BoardCardKind.SUGGESTED_TASKS,
    val tasks: List<BoardSuggestedTaskResponse>,
) : BoardCardContent

/** One suggested task, with the plain reasons it was suggested and the id to claim it by. */
data class BoardSuggestedTaskResponse(
    val taskId: UUID,
    val title: String,
    val url: String?,
    val reasons: List<String>,
)
