package com.sprintstart.sprintstartbackend.onboarding.model.response.board

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardOwner
import com.sprintstart.sprintstartbackend.onboarding.model.response.arrival.ArrivalStepResponse
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
    JsonSubTypes.Type(value = ArrivalStepsContent::class, name = "ARRIVAL_STEPS"),
    JsonSubTypes.Type(value = OpenPullRequestsContent::class, name = "OPEN_PULL_REQUESTS"),
    JsonSubTypes.Type(value = CurrentTaskContent::class, name = "CURRENT_TASK"),
    JsonSubTypes.Type(value = SuggestedTasksContent::class, name = "SUGGESTED_TASKS"),
    JsonSubTypes.Type(value = CompetencyProgressContent::class, name = "COMPETENCY_PROGRESS"),
    JsonSubTypes.Type(value = MemoryRecapContent::class, name = "MEMORY_RECAP"),
    JsonSubTypes.Type(value = DiagramContent::class, name = "DIAGRAM"),
    JsonSubTypes.Type(value = NoteContent::class, name = "NOTE"),
    JsonSubTypes.Type(value = LinkContent::class, name = "LINK"),
    JsonSubTypes.Type(value = ChecklistContent::class, name = "CHECKLIST"),
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
 * What still has to be true before this hire can work, and what they have already settled.
 *
 * ### There is no completion figure here, deliberately
 *
 * The counts are reported per rigor and there is no total to divide by. ⚠️ A single completion
 * percentage counts a ticked box exactly like a passed check, and that conflation is what makes
 * such a number meaningless. A client says what is known — *"5 confirmed by the system · 2 you
 * told us about · 2 outstanding"* — rather than a percentage averaging two kinds of evidence.
 */
data class ArrivalStepsContent(
    override val kind: BoardCardKind = BoardCardKind.ARRIVAL_STEPS,
    val steps: List<ArrivalStepResponse>,
    val observedCount: Int,
    val declaredCount: Int,
    val outstandingCount: Int,
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

/**
 * Something the hire wrote down.
 *
 * The one card whose text the board did not read from anywhere: it is theirs, and it is rendered as
 * theirs rather than quoted back as a fact about the project.
 */
data class NoteContent(
    override val kind: BoardCardKind = BoardCardKind.NOTE,
    val text: String,
) : BoardCardContent

/** A link the hire kept. A null [label] means show the URL — worse to read, but always true. */
data class LinkContent(
    override val kind: BoardCardKind = BoardCardKind.LINK,
    val url: String,
    val label: String?,
) : BoardCardContent

/** A list the hire ticks off — the only card whose content changes by being used. */
data class ChecklistContent(
    override val kind: BoardCardKind = BoardCardKind.CHECKLIST,
    val title: String?,
    val items: List<ChecklistItemResponse>,
) : BoardCardContent

/** One checklist item, identified so that ticking it is an edit to the line and not to a position. */
data class ChecklistItemResponse(
    val id: UUID,
    val text: String,
    val done: Boolean,
)

/**
 * What the hire has shown they can do, and what they are short of.
 *
 * Split into held and in-progress rather than given a percentage, for the reason the ramp gives for
 * having none: a percentage of somebody's competence is a number nobody can act on, and the two
 * lists say the same thing in a form they can.
 *
 * Level-0 rows are excluded. They mean "asked, saw no evidence" — placed but unknown — and reading
 * them as competencies would report a skill the hire has never shown.
 */
data class CompetencyProgressContent(
    override val kind: BoardCardKind = BoardCardKind.COMPETENCY_PROGRESS,
    /** Meets its target level: shown, not merely started. */
    val held: List<BoardCompetencyResponse>,
    /** Progress made, target not yet met. */
    val inProgress: List<BoardCompetencyResponse>,
) : BoardCardContent

/** One competency, with the bar it is measured against — never a score out of a hundred. */
data class BoardCompetencyResponse(
    val competencyKey: String,
    val label: String,
    val level: Int,
    val targetLevel: Int,
)

/**
 * A picture of how some part of this project fits together.
 *
 * The card that carries the one extension the board's rules ever got: **the model may choose the
 * question, it never writes the answer.** [subject] is the mentor's — only the conversation knows
 * what was just being explained — and everything else here is derived from the project's own
 * material, one citation per box, ungrounded boxes dropped before they were ever returned.
 *
 * A board read serves the **last picture drawn**, never a fresh one: assembling costs a generation
 * and this card hydrates on every page load. The client revalidates it afterwards through the
 * diagram endpoint, which is where the cache is checked against the current corpus. So [assembledAt]
 * is not decoration — a diagram is a claim about code as it was at a moment, and the reader is
 * entitled to know which moment.
 *
 * [nodes] empty with a [reason] is an ordinary state: the corpus may have nothing to say about this
 * subject, or too little to make a picture rather than a word. An empty diagram is never dressed up
 * as an explanation.
 */
data class DiagramContent(
    override val kind: BoardCardKind = BoardCardKind.DIAGRAM,
    /** The question this diagram answers, as the mentor asked it. */
    val subject: String,
    val summary: String?,
    val nodes: List<BoardDiagramNodeResponse>,
    val edges: List<BoardDiagramEdgeResponse>,
    /** The material the picture drew on, so "this is wrong" has somewhere to point. */
    val sources: List<BoardDiagramSourceResponse>,
    /** When this picture was drawn; null when it never has been. */
    val assembledAt: Instant?,
    /** Why there is no picture, when there is none. Null whenever [nodes] is non-empty. */
    val reason: String?,
) : BoardCardContent

/**
 * One box.
 *
 * [citations] is what separates a diagram from a drawing: every box asserts this project contains
 * this part, and the citation is how a reader checks it. Never empty — an ungrounded node is dropped
 * upstream rather than shown unsourced.
 */
data class BoardDiagramNodeResponse(
    val id: String,
    val label: String,
    /** What this is — COMPONENT, FILE, SERVICE, DATA, STEP, EXTERNAL, or OTHER when unsettled. */
    val kind: String,
    val summary: String?,
    val citations: List<BoardDiagramCitationResponse>,
)

/** One arrow. Both ends name a box in the same diagram. */
data class BoardDiagramEdgeResponse(
    val fromId: String,
    val toId: String,
    /** FLOWS_TO, DEPENDS_ON, CONTAINS, or RELATES_TO when the evidence does not settle it. */
    val kind: String,
    val label: String?,
)

/** Where a box came from. A source with no URL is still named — unopenable beats unattributed. */
data class BoardDiagramCitationResponse(
    val filename: String,
    val sourceUrl: String?,
)

data class BoardDiagramSourceResponse(
    val filename: String,
    val sourceUrl: String?,
    val artifactType: String?,
)

/**
 * What the mentor remembers about this hire, in the mentor's own words.
 *
 * The one card whose content a model wrote, which is why it is labelled as such rather than
 * presented as fact. It exists because the buddy's memory is what carries continuity across visits
 * now that the transcript is not replayed, and until now a hire could not see what their mentor
 * thinks it knows about them — let alone tell it that it is wrong.
 *
 * [memory] is null before the first visit has been folded, which is a real state and reads as "we
 * have not talked yet" rather than as an empty card.
 */
data class MemoryRecapContent(
    override val kind: BoardCardKind = BoardCardKind.MEMORY_RECAP,
    val memory: String?,
    /**
     * How many messages the memory covers.
     *
     * Shown because it is the honest measure of how much the mentor is working from: a memory
     * folded from two messages and one folded from two hundred read the same otherwise.
     */
    val messagesRemembered: Int,
) : BoardCardContent
