package com.sprintstart.sprintstartbackend.ingestion.external

import com.sprintstart.sprintstartbackend.ingestion.external.model.ArtifactDto
import java.time.Instant
import java.util.UUID

/**
 * Exported ingestion-module API for other backend modules.
 *
 * Exposes read-only ingestion metadata about a component without leaking the ingestion module's
 * internal entities. Other modules should depend on this interface instead of querying the
 * ingestion repositories directly.
 *
 * One method per distinct question another module asks of the corpus; the count tracks how many
 * things ingestion is asked, not an interface doing too many jobs.
 */
@Suppress("TooManyFunctions")
interface ArtifactIngestionApi {
    /**
     * Returns when a component (`owner/repo`) was first ingested, or null when it has no ingested
     * artifacts.
     */
    fun getFirstIngestedAt(component: String): Instant?

    /**
     * Batch variant of [getFirstIngestedAt]. Only components with a known timestamp are present in
     * the returned map.
     */
    fun getFirstIngestedAt(components: Collection<String>): Map<String, Instant>

    /**
     * Returns whether an ingested artifact with [artifactId] exists.
     */
    fun exists(artifactId: UUID): Boolean

    /**
     * Returns the content hash of an ingested artifact, or null if it has none on record.
     *
     * Callers that need to distinguish "no such artifact" from "artifact has no hash" should check
     * [exists] first.
     */
    fun getHash(artifactId: UUID): String?

    /**
     * Returns whether the artifact exists and belongs to the specified project.
     */
    fun existsInProject(projectId: UUID, artifactId: UUID): Boolean

    /**
     * Summarizes what one GitHub account has authored inside a project's ingested artifacts.
     *
     * Reads only the corpus the project already has connected -- no GitHub call -- so it can
     * describe a person's prior involvement without any new data source. Only issues and pull
     * requests carry an author, so commits and files never contribute (see `Artifact.authorLogin`).
     *
     * @param projectId The project whose ingested artifacts to look at.
     * @param authorLogin Lower-cased GitHub login to attribute artifacts to.
     * @return One entry per artifact authored by that account; empty when there are none.
     */
    fun getAuthoredWork(projectId: UUID, authorLogin: String): List<AuthoredArtifact>

    /**
     * The pull requests one GitHub account has authored in a project, with the timestamps
     * onboarding measures against.
     *
     * Separate from [getAuthoredWork] because it answers a different question with a different
     * shape: that one describes involvement (where, what kind), this one describes a lifecycle
     * (opened, answered, merged). Folding the timestamps into `AuthoredArtifact` would put a
     * pull-request-only concern on a type that also covers issues.
     *
     * Reads only artifacts already ingested -- no GitHub call.
     */
    fun getAuthoredPullRequests(projectId: UUID, authorLogin: String): List<AuthoredPullRequest>

    /**
     * The tracked issues assigned to one person in a project, with the timestamps onboarding
     * measures against.
     *
     * The non-code counterpart of [getAuthoredPullRequests], and the reason a role that never opens
     * a pull request can still be *observed* rather than vouched for. Reads only issues already
     * ingested — no call to the tracker.
     *
     * ⚠️ **Attribution is by display name**, because that is the only identity the ingested Jira
     * data carries. A blank or unmatched name yields nothing, which callers already read as "no
     * attribution possible" rather than "did no work".
     *
     * @param projectId The project whose ingested issues to look at.
     * @param assigneeDisplayName The name the person appears under in the tracker, as stored.
     * @return One entry per issue currently assigned to that name; empty when there are none.
     */
    fun getAssignedIssues(projectId: UUID, assigneeDisplayName: String): List<AssignedIssue>

    /**
     * The ingested artifact one starter-work task was mined from, by its source id.
     *
     * Exists so task-scoped orientation can be assembled from what the issue *actually says* — its
     * body and its labels — rather than from the one-line summary the mining pass wrote. Reads only
     * artifacts already ingested; no GitHub call.
     *
     * @param sourceId The backend's stable identifier, e.g. `github:org/repo:ISSUE:123`.
     * @return The artifact's own text, or null when nothing with that source id is ingested.
     */
    fun getTaskSource(sourceId: String): TaskSourceArtifact?

    /**
     * Every open tracker issue in a project, whoever it belongs to.
     *
     * The corpus can already answer "which issues could a newcomer take?" without a model call —
     * that filter is deterministic — so this exists to let a person browse the same material mining
     * reads. ⚠️ **Assigned issues are returned too, marked**, unlike mining's candidate list: mining
     * must skip them, because proposing somebody else's work is a wrong answer a hire cannot detect,
     * but a person browsing can see who holds one and decide anyway. Filtering here would turn that
     * exclusion into an absence nobody can account for.
     *
     * Reads only artifacts already ingested; no call to GitHub or the tracker.
     *
     * @param projectId The project whose ingested issues to list.
     * @return One entry per open issue; empty when the project has none ingested.
     */
    fun getOpenIssues(projectId: UUID): List<IngestedIssue>

    /**
     * One ingested issue by its source id, open or not.
     *
     * The single-row counterpart of [getOpenIssues], for acting on an issue somebody browsed.
     * Returns null for a source id that is not ingested **or belongs to something that is not an
     * issue** — a pull request is not work to hand a newcomer, and silently accepting one would put
     * it in the pool under an issue's name.
     */
    fun getIssue(sourceId: String): IngestedIssue?

    /**
     * How responsive each of a project's repositories is to pull requests.
     *
     * A property of the *repository*, not of any one author: it is derived from every ingested pull
     * request in the project, so it describes the people who review there. That is the only honest
     * grain available — ingestion records when a pull request first got a response, but not **who**
     * responded, so per-person responsiveness cannot be computed today.
     *
     * @param projectId The project whose repositories to characterise.
     * @return One entry per repository that has at least one ingested pull request.
     */
    fun getRepositoryResponsiveness(projectId: UUID): List<RepositoryResponsiveness>

    /**
     * Finds and retrieves an artifact by its unique identifier.
     *
     * @param artifactId The unique identifier of the artifact to be retrieved.
     * @return The artifact details wrapped in an [ArtifactDto] object.
     */
    fun findArtifactById(artifactId: UUID): ArtifactDto?
}

/**
 * How long a repository takes to answer a pull request, and how many go unanswered.
 *
 * Exists because "an unblocked task owned by someone who never answers is not an unblocked task":
 * a newcomer's first contribution succeeding depends at least as much on somebody responding as on
 * the task being well scoped.
 *
 * [medianHoursToFirstResponse] is null when no ingested pull request here has been answered at all
 * — which is *worse* than a slow median, not unknown, and callers must not read it as "no data".
 */
data class RepositoryResponsiveness(
    val repositoryFullName: String,
    val medianHoursToFirstResponse: Long?,
    val answeredCount: Int,
    val unansweredCount: Int,
)

/**
 * One ingested tracker issue, with everything a person needs to judge it as starter work.
 *
 * Carries the issue's own text and labels like [TaskSourceArtifact], plus the two fields a
 * *selection* turns on and orientation does not: [state] and [hasAssignee].
 *
 * ⚠️ **[hasAssignee] is three-valued and null means *we do not know*, never "nobody".** GitHub
 * issues have assignees this system does not ingest, so a null here is an absence of information
 * about the issue, not information that it is free. A caller rendering it must say so; a caller
 * filtering on it must treat only a definite `true` as "somebody has this".
 *
 * [state] is `"OPEN"` / `"CLOSED"` as the tracker reports it, folded to those two by the mappers,
 * and null on rows ingested before state was captured — unknown, again, rather than open.
 */
data class IngestedIssue(
    val sourceId: String,
    /** Which system it came from, as a `SourceSystem` name — `GITHUB`, `JIRA`. */
    val tracker: String,
    val title: String?,
    val body: String?,
    val labels: List<String>,
    val sourceUrl: String?,
    val state: String?,
    val hasAssignee: Boolean?,
    /** When the issue last changed at its source; null when the source never said. */
    val updatedAtSource: Instant?,
)

/**
 * The text of the artifact a task came from.
 *
 * Carries body and labels, unlike [AuthoredArtifact], because here the content *is* the point:
 * orientation is aimed at what this task involves, so the retrieval it drives has to see the task's
 * own words.
 */
data class TaskSourceArtifact(
    val title: String?,
    val body: String?,
    val labels: List<String>,
    val sourceUrl: String?,
)

/**
 * One pull request a person authored, reduced to its lifecycle.
 *
 * [firstResponseAt] is the earliest reaction from anyone else -- a review or a comment. A null
 * means nobody has responded yet, which is a finding rather than missing data: an unanswered pull
 * request is the failure onboarding instrumentation exists to catch.
 */
data class AuthoredPullRequest(
    val artifactId: UUID,
    val openedAt: Instant?,
    val firstResponseAt: Instant?,
    val mergedAt: Instant?,
    val state: String?,
    /**
     * How many reviews asked the author to change this pull request.
     *
     * Zero is the whole point: "done with no rework" is half the operational definition of
     * autonomy, and merge state alone cannot tell a clean change from one sent back three times.
     */
    val changesRequestedCount: Int = 0,
    val repositoryFullName: String? = null,
    /** The pull request's own number (e.g. 142), parsed from its source id. Null if unparseable. */
    val number: Int? = null,
    /** The pull request title, so a hire can be told *which* pull request, not just how many. */
    val title: String? = null,
    /** A link straight to the pull request on the host, when the artifact recorded one. */
    val sourceUrl: String? = null,
) {
    /**
     * Truly open: neither merged nor closed-without-merging.
     *
     * A pull request closed without merging also has a null [mergedAt], so merge state alone would
     * miscount it as open — [state] is what separates a live pull request from a closed one. Merged
     * pull requests carry a [mergedAt]; closed-unmerged ones report state `CLOSED`; only a genuinely
     * open one is neither. An unknown ([state] null) unmerged pull request is treated as open, which
     * only matters for data that predates state capture.
     */
    val isOpen: Boolean
        get() = mergedAt == null && !"CLOSED".equals(state, ignoreCase = true)
}

/**
 * One tracked issue assigned to a person, reduced to the four moments onboarding measures.
 *
 * Deliberately the same four as [AuthoredPullRequest] — opened, first answered, accepted, sent back
 * — because that is the whole claim of the contribution stream: a draft plan returned for changes
 * and a pull request with changes requested are the same event, so neither needs its own metrics.
 *
 * ### What "accepted" means here, and what it refuses to mean
 *
 * ⚠️ **[acceptedAt] is null when the person moved their own issue to Done.** Closing your own
 * ticket is a claim, not an observation, and the whole reason this source exists is to produce
 * evidence nobody had to vouch for. Such an issue stays in flight rather than being downgraded to a
 * weaker acceptance: absent evidence stays "no evidence".
 */
data class AssignedIssue(
    val artifactId: UUID,
    /** When the issue became this person's — the assignment, falling back to when it was created. */
    val openedAt: Instant?,
    /** The first comment by anybody other than the assignee. */
    val firstResponseAt: Instant?,
    /** When somebody else moved it to a done status, or null — see the note above. */
    val acceptedAt: Instant?,
    /**
     * How many times somebody else moved the issue out of a status the assignee had put it in.
     *
     * The tracker equivalent of a review asking for changes, and derived from the changelog rather
     * than guessed: "done with no rework" is half the operational definition of autonomy, so
     * reporting a flat zero here would quietly hand every tracked issue an autonomy signal it had
     * not earned.
     */
    val returnedCount: Int = 0,
    /** The issue key (e.g. `ONB-42`), so a hire can be told *which* issue. */
    val key: String? = null,
    val title: String? = null,
    val sourceUrl: String? = null,
)

/**
 * One artifact a person authored, reduced to what a prior can be built from.
 *
 * Deliberately carries no title or body: the point is *that* somebody has worked here and on what
 * kind of thing, not the content of their work.
 */
data class AuthoredArtifact(
    val artifactType: String,
    val repositoryFullName: String?,
    val labels: List<String>,
)
