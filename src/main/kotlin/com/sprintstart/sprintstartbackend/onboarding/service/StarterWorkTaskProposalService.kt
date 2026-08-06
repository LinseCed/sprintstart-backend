package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.ingestion.external.IngestedIssue
import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CandidatePoolState
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.TaskType
import com.sprintstart.sprintstartbackend.onboarding.external.model.AiProgressEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.StarterWorkOutcome
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.starterwork.CreateStarterWorkTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.starterwork.PromoteStarterWorkCandidateRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.GenerateStarterWorkResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.RankedStarterWorkTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkCandidateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkTaskProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.UnreviewedStarterWorkResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.ProjectMembershipApi
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates AI-mined starter-work task proposals, PM review, and hire-fit matching.
 *
 * Reviewing a proposal marks the row and nothing else -- a goal points at the task itself.
 *
 * [StarterWorkTaskProposal.competencyKeys] is one of the four signals [StarterWorkMatcher] ranks a
 * task by. ⚠️ It says what the work exercises; it does not gate who may claim it.
 */
@Service
@Suppress("TooManyFunctions")
class StarterWorkTaskProposalService(
    private val onboardingAiClient: OnboardingAiClient,
    private val competencyRepository: CompetencyRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val githubHistoryPriorService: GithubHistoryPriorService,
    private val artifactIngestionApi: ArtifactIngestionApi,
    private val userApi: UserApi,
    private val projectMembershipApi: ProjectMembershipApi,
    private val trackService: TrackService,
    private val json: Json,
    transactionManager: PlatformTransactionManager,
) {
    // The AI call is a long-running suspend operation, so it must not run inside a
    // transaction (a DB connection would be pinned for its whole duration).
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Triggers AI starter-work mining and persists the results as `LIVE`.
     *
     * Issues already in the pool (`LIVE` -- not `REJECTED`, so a rejection doesn't permanently
     * block re-mining if circumstances change) and the current live
     * competency keys are sent to the stateless AI service so it can dedupe and ground competency
     * tags. The AI call runs outside any transaction.
     *
     * @return Summary of how many tasks were newly proposed.
     */
    suspend fun generate(): GenerateStarterWorkResponse {
        val (activeSourceIds, activeCompetencyKeys) = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadActiveState() }!!
        }
        val outcome = onboardingAiClient.proposeStarterWork(
            activeSourceIds = activeSourceIds,
            activeCompetencyKeys = activeCompetencyKeys,
        )
        val persisted = withContext(Dispatchers.IO) {
            txTemplate.execute { persistProposals(outcome) }!!
        }
        return GenerateStarterWorkResponse(
            status = outcome.status,
            tasksProposed = persisted.tasksPersisted,
            notes = outcome.notes + persisted.skipped,
        )
    }

    /**
     * The streaming twin of [generate].
     *
     * Relays the AI's `stage`/`item` events so a PM watches the pool fill one task at a time, and
     * on the terminal `done` persists the tasks through the very same [persistProposals] the sync
     * path uses — so the stored pool equals the non-streaming path (invariant 2). A task the backend
     * re-gates away on persist (already pooled) is announced as a `warning` before the `done`, never
     * silently dropped (invariant 1). A stream failure becomes a synthesised terminal `error`.
     */
    suspend fun streamGenerate(): Flow<AiProgressEvent> {
        val active = withContext(Dispatchers.IO) { readTxTemplate.execute { loadActiveState() }!! }
        return flow {
            onboardingAiClient
                .streamStarterWork(
                    activeSourceIds = active.sourceIds,
                    activeCompetencyKeys = active.competencyKeys,
                ).collect { event ->
                    if (event.type == AiProgressEvent.DONE) {
                        decodeOutcome(event)?.let { outcome ->
                            val persisted = withContext(Dispatchers.IO) {
                                txTemplate.execute { persistProposals(outcome) }!!
                            }
                            persisted.skipped.forEach { emit(AiProgressEvent.warning(STARTER_WORK, it)) }
                        }
                    }
                    emit(event)
                }
        }.catch { cause ->
            logger.warn("Starter-work mining stream failed: {}", cause.message)
            emit(AiProgressEvent.error(STARTER_WORK, "Starter-work mining is temporarily unavailable"))
        }
    }

    private fun decodeOutcome(event: AiProgressEvent): StarterWorkOutcome? =
        event.result?.let {
            runCatching { json.decodeFromJsonElement<StarterWorkOutcome>(it) }
                .onFailure { e -> logger.warn("Could not decode streamed starter-work result: {}", e.message) }
                .getOrNull()
        }

    private data class ActiveState(
        val sourceIds: List<String>,
        val competencyKeys: List<String>,
    )

    private fun loadActiveState(): ActiveState {
        val sourceIds = starterWorkTaskProposalRepository
            .findAllByStatusIn(listOf(ProposalStatus.LIVE, ProposalStatus.REJECTED))
            .map { it.sourceId }
        val competencyKeys = competencyRepository.findAll().map { it.key }
        return ActiveState(sourceIds, competencyKeys)
    }

    /**
     * Persists the outcome's tasks as `LIVE` rows, re-applying the backend's own dedup: a task
     * whose issue is already in the pool is not mined again. The AI dedupes against the active pool
     * the caller sends, but this is the authoritative check at write time -- and it closes the
     * window where two mining runs could double-pool an issue. Returns how many landed and a human
     * note per skipped task, so the streaming path can surface each skip as a `warning`.
     */
    private fun persistProposals(outcome: StarterWorkOutcome): PersistResult {
        // REJECTED counts as already-pooled, which is what makes a rejection **sticky**: a task
        // somebody turned down must not be mined back into existence on the next crawl, or they
        // turn it down again, forever. The row survives precisely so this check can see it.
        val alreadyPooled = starterWorkTaskProposalRepository
            .findAllByStatusIn(listOf(ProposalStatus.LIVE, ProposalStatus.REJECTED))
            .map { it.sourceId }
            .toMutableSet()
        val skipped = mutableListOf<String>()
        var tasks = 0
        outcome.tasks.forEach { proposed ->
            if (!alreadyPooled.add(proposed.sourceId)) {
                skipped.add("\"${proposed.title}\" is already in the pool — not re-proposed")
                return@forEach
            }
            starterWorkTaskProposalRepository.save(
                StarterWorkTaskProposal(
                    sourceId = proposed.sourceId,
                    title = proposed.title,
                    summary = proposed.summary.takeIf { it.isNotBlank() },
                    rationale = proposed.rationale.takeIf { it.isNotBlank() },
                    sourceUrl = proposed.citations.firstOrNull()?.sourceUrl,
                    competencyKeys = proposed.competencyKeys.toMutableList(),
                    // Claimable immediately, and honest about nobody having looked yet. The
                    // matcher demotes it until somebody does.
                    reviewed = false,
                ),
            )
            tasks++
        }
        return PersistResult(tasks, skipped)
    }

    private data class PersistResult(
        val tasksPersisted: Int,
        val skipped: List<String>,
    )

    /**
     * Returns the starter-work tasks currently awaiting PM review.
     */
    @Transactional(readOnly = true)
    fun listUnreviewed(): UnreviewedStarterWorkResponse =
        UnreviewedStarterWorkResponse(
            tasks = starterWorkTaskProposalRepository
                .findAllByStatusAndReviewedFalse(ProposalStatus.LIVE)
                .map { it.toResponse() },
        )

    /**
     * Returns the live starter-work pool, for a PM choosing a task to author orientation for.
     *
     * The whole live set, ordered by title, not scoped to a project: tasks are a global
     * pool (the entity has no `projectId`), and orientation is per-`(task, project)` only because the
     * corpus a packet is grounded in is per-project.
     */
    @Transactional(readOnly = true)
    fun listPool(): List<StarterWorkTaskProposalResponse> =
        starterWorkTaskProposalRepository
            .findAllByStatus(ProposalStatus.LIVE)
            .sortedBy { it.title }
            .map { it.toResponse() }

    /**
     * Records that a person has looked at this task and is happy with it.
     *
     * It was already claimable — reviewing does not admit it to the pool, it lifts the demotion
     * `StarterWorkMatcher` applies while nobody has vouched for it. Idempotent: reviewing twice is
     * the same statement made twice.
     *
     * @throws ResponseStatusException 404 if no task matches [id]; 409 if it was rejected.
     */
    @Transactional
    fun markReviewed(id: UUID): StarterWorkTaskProposalResponse {
        val proposal = findLiveProposal(id)
        proposal.reviewed = true
        proposal.decidedAt = Instant.now()
        return proposal.toResponse()
    }

    /**
     * Creates a hand-authored starter-work task, with no AI mining in the loop.
     *
     * Born reviewed and materialised straight away: writing a task is vouching for it, so it never
     * joins the unreviewed queue. The task
     * has no ingested source, so a stable synthetic [sourceId] is minted -- unique per task, and by
     * design not a `github:owner/name:...` id, so fit-ranking's repository-responsiveness and
     * ingested-label lookups simply find nothing for it rather than misattributing it, both of which
     * already degrade gracefully to "no signal".
     *
     * @throws ResponseStatusException 400 if the title is blank.
     */
    @Transactional
    fun createTask(request: CreateStarterWorkTaskRequest): StarterWorkTaskProposalResponse {
        if (request.title.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank")
        }
        val proposal = starterWorkTaskProposalRepository.save(
            StarterWorkTaskProposal(
                sourceId = HAND_AUTHORED_SOURCE_PREFIX + UUID.randomUUID(),
                title = request.title.trim(),
                summary = request.summary?.trim()?.takeIf { it.isNotBlank() },
                rationale = null,
                sourceUrl = request.sourceUrl?.trim()?.takeIf { it.isNotBlank() },
                competencyKeys = request.competencyKeys.toMutableList(),
                status = ProposalStatus.LIVE,
                // Somebody wrote it, so it is reviewed by construction -- there is nothing to
                // vouch for back to them.
                reviewed = true,
                decidedAt = Instant.now(),
                onboardingTrackKey = request.onboardingTrackKey?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
        return proposal.toResponse()
    }

    /**
     * The open issues in a project's corpus, each marked with where it stands in the pool.
     *
     * Deterministic and LLM-free: this is the same material mining reads, shown to a person
     * instead. Nothing is scored, because the judgement being made here is the reader's.
     *
     * ⚠️ **Assigned issues are returned, marked, and so are issues already pooled or removed.** The
     * filtering is the client's to do, and it can only offer an honest filter if it is holding the
     * things being filtered: an issue that simply is not in the list gives a PM no way to tell
     * "somebody else has this" from "not ingested". [CandidatePoolState] and the three-valued
     * `hasAssignee` carry that, so nothing has to be inferred from an absence.
     *
     * Ordered newest-changed first, which is a fact about the issues rather than an opinion about
     * them; ties fall back to the source id so the order is stable across calls.
     *
     * @param projectId The project whose ingested issues to browse.
     */
    @Transactional(readOnly = true)
    fun listCandidates(projectId: UUID): List<StarterWorkCandidateResponse> {
        val poolStateBySourceId = starterWorkTaskProposalRepository
            .findAllByStatusIn(listOf(ProposalStatus.LIVE, ProposalStatus.REJECTED))
            .associate { it.sourceId to it.status.toPoolState() }

        return artifactIngestionApi
            .getOpenIssues(projectId)
            .mapNotNull { issue ->
                // A title is what a person picks from, and there is nothing sensible to render for
                // an issue that has none -- so it is dropped rather than shown as an empty row.
                val title = issue.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val body = issue.body?.takeIf { it.isNotBlank() }
                StarterWorkCandidateResponse(
                    sourceId = issue.sourceId,
                    tracker = issue.tracker,
                    title = title,
                    excerpt = body?.take(CANDIDATE_EXCERPT_CHARS),
                    excerptTruncated = (body?.length ?: 0) > CANDIDATE_EXCERPT_CHARS,
                    labels = issue.labels,
                    sourceUrl = issue.sourceUrl,
                    hasAssignee = issue.hasAssignee,
                    poolState = poolStateBySourceId[issue.sourceId] ?: CandidatePoolState.AVAILABLE,
                    updatedAtSource = issue.updatedAtSource,
                )
            }.sortedWith(
                compareByDescending<StarterWorkCandidateResponse> { it.updatedAtSource }
                    .thenBy { it.sourceId },
            )
    }

    private fun ProposalStatus.toPoolState(): CandidatePoolState =
        when (this) {
            ProposalStatus.LIVE -> CandidatePoolState.IN_POOL
            ProposalStatus.REJECTED -> CandidatePoolState.REMOVED
        }

    /**
     * Puts one browsed corpus issue into the pool, live and reviewed.
     *
     * Deliberately the same landing place as [createTask]: somebody looked at this issue and vouched
     * for it, which is the whole content of "reviewed". ⚠️ **This is not an approval gate in front
     * of mining** — mining keeps landing tasks live without anybody's involvement, and this only
     * adds a way in for an issue mining did not pick.
     *
     * The title and link come from the ingested issue, so the pool cannot disagree with the tracker
     * about what an issue is called.
     *
     * **What it refuses, and what it deliberately does not:**
     * - An issue **already in the pool** is a 409 rather than a second row, and one **removed from
     *   the pool** is a 409 too: rejection is sticky, and letting promotion undo it by accident
     *   would mean somebody re-rejecting the same issue after every crawl.
     * - An issue that is **closed at its source** is a 409. Work that is already done is the one
     *   thing a newcomer must not be handed, and the browser only lists open issues, so this can
     *   only be reached by a race or a stale page.
     * - An **assigned** issue is accepted. Mining skips those because it cannot ask anybody; a
     *   person promoting one can see who holds it and has decided anyway. That override is the
     *   reason the browser shows them at all.
     *
     * @throws ResponseStatusException 404 if no issue with that source id is ingested; 400 if the
     * ingested issue has no title; 409 if it is already pooled, was removed, or is closed.
     */
    @Transactional
    fun promoteCandidate(request: PromoteStarterWorkCandidateRequest): StarterWorkTaskProposalResponse {
        val sourceId = request.sourceId.trim()
        val issue = loadPromotableIssue(sourceId)
        val title = titleOf(sourceId, issue)
        refuseIfAlreadyDecided(sourceId)

        val proposal = starterWorkTaskProposalRepository.save(
            StarterWorkTaskProposal(
                sourceId = sourceId,
                title = title,
                summary = request.summary?.trim()?.takeIf { it.isNotBlank() },
                // Nothing judged this issue, so there is no rationale to state. Writing one here
                // would be the app putting words in the promoter's mouth.
                rationale = null,
                sourceUrl = issue.sourceUrl,
                competencyKeys = request.competencyKeys.toMutableList(),
                status = ProposalStatus.LIVE,
                reviewed = true,
                decidedAt = Instant.now(),
                onboardingTrackKey = request.onboardingTrackKey?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
        return proposal.toResponse()
    }

    /** The ingested issue, if there is one and it is still work somebody could be handed. */
    private fun loadPromotableIssue(sourceId: String): IngestedIssue {
        val issue = artifactIngestionApi.getIssue(sourceId)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "No ingested issue found with source id: $sourceId",
            )
        // Only a definite "CLOSED" refuses. A row whose state was never captured is unknown, and
        // refusing on unknown would make an un-promotable issue out of one nobody can check.
        if (CLOSED_STATE.equals(issue.state, ignoreCase = true)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Issue $sourceId is closed at its source and is not work to hand a newcomer",
            )
        }
        return issue
    }

    private fun titleOf(sourceId: String, issue: IngestedIssue): String =
        issue.title?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Issue $sourceId has no title and cannot be put in the pool",
            )

    /**
     * Refuses an issue that already has a proposal, live or rejected.
     *
     * ⚠️ The rejected half is the load-bearing one: rejection is sticky, so promotion must not
     * become the accidental way to undo it.
     */
    private fun refuseIfAlreadyDecided(sourceId: String) {
        val existing = starterWorkTaskProposalRepository.findBySourceId(sourceId) ?: return
        val reason = when (existing.status) {
            ProposalStatus.LIVE -> "Issue $sourceId is already in the starter-work pool"
            ProposalStatus.REJECTED ->
                "Issue $sourceId was removed from the starter-work pool and is not re-addable"
        }
        throw ResponseStatusException(HttpStatus.CONFLICT, reason)
    }

    /**
     * Takes a task out of the pool, permanently.
     *
     * **Sticky**: the row is kept and mining consults it, so a task somebody turned down is never
     * mined back into existence — otherwise they would turn it down again after every crawl. Same
     * rule as a competency tombstone, expressed here as a status rather than a separate table
     * because the row is already keyed by the `sourceId` mining deduplicates on.
     *
     * @throws ResponseStatusException 404 if no task matches [id]; 409 if it was already rejected.
     */
    @Transactional
    fun reject(id: UUID, reason: String?): StarterWorkTaskProposalResponse {
        val proposal = findLiveProposal(id)
        proposal.status = ProposalStatus.REJECTED
        proposal.decidedAt = Instant.now()
        proposal.rejectionReason = reason
        return proposal.toResponse()
    }

    /**
     * Ranks the current `LIVE` starter-work pool by fit for the authenticated user on a project.
     *
     * A hire must be told in one line why a task was suggested, and an embedding distance cannot
     * say. The ranking is deterministic, local and self-explaining (see [StarterWorkMatcher]) —
     * which also keeps a network round trip off a hire's request path.
     *
     * Project-scoped, unlike the previous version: two of the signals (prior involvement and
     * repository responsiveness) are only meaningful inside one project's ingested corpus.
     *
     * @throws ResponseStatusException 404 if no user matches [authId].
     */
    @Transactional(readOnly = true)
    fun matchForUser(authId: String, projectId: UUID): List<RankedStarterWorkTaskResponse> {
        val userId = userApi.getUserIdByAuthId(authId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId")
        }
        return matchForUserId(userId, projectId)
    }

    /**
     * Ranks the current `LIVE` starter-work pool by fit for an already-resolved user on a
     * project.
     *
     * The user-id counterpart of [matchForUser], for callers that hold a user id rather than an auth
     * subject (e.g. the buddy agent ranking tasks for the caller).
     *
     * @param userId Internal SprintStart user identifier.
     * @param projectId The project whose responsiveness/involvement signals score the pool.
     */
    @Transactional(readOnly = true)
    fun matchForUserId(userId: UUID, projectId: UUID): List<RankedStarterWorkTaskResponse> {
        val pool = suggestablePool(userId, projectId)
        if (pool.isEmpty()) return emptyList()

        val profile = buildProfile(userId)
        val responsivenessByRepo = artifactIngestionApi
            .getRepositoryResponsiveness(projectId)
            .associateBy { it.repositoryFullName }

        return pool
            .map { proposal ->
                val features = featuresOf(proposal)
                val responsiveness = features.repositoryFullName
                    ?.let { responsivenessByRepo[it] }
                    ?.let { StarterWorkMatcher.Responsiveness(it.medianHoursToFirstResponse, it.unansweredCount) }
                val scored = StarterWorkMatcher.score(profile, features, responsiveness)
                RankedStarterWorkTaskResponse(
                    task = proposal.toResponse(),
                    score = scored.score,
                    matchedCompetencyKeys = scored.matchedCompetencyKeys,
                    taskType = features.taskType,
                    reasons = scored.reasons,
                )
            }
            // Ties broken by the oldest task first, so the ranking is stable across calls rather
            // than reshuffling equally-good suggestions every time the hire reloads.
            .sortedWith(compareByDescending<RankedStarterWorkTaskResponse> { it.score }.thenBy { it.task.title })
    }

    /**
     * The approved tasks worth suggesting to this hire: their own track's, plus every unscoped one.
     *
     * Filtering here rather than scoring it as a signal is deliberate. A task for another role is
     * not a *worse* suggestion, it is the wrong job — offering a Scrum Master "fix the null pointer
     * in checkout", however far down the list, is noise that costs trust in everything above it.
     *
     * An **unscoped** task stays in every hire's pool. Null means "any track", which is how every
     * task behaved before tracks existed and the only honest reading of a mined issue: mining
     * cannot know which role an issue suits, so it must not pretend to.
     */
    private fun suggestablePool(userId: UUID, projectId: UUID): List<StarterWorkTaskProposal> {
        val member = projectMembershipApi.getProjectMembers(projectId).firstOrNull { it.userId == userId }
        val trackKey = member?.let { trackService.forMember(it).key }
        return starterWorkTaskProposalRepository
            .findAllByStatus(ProposalStatus.LIVE)
            .filter { it.onboardingTrackKey == null || it.onboardingTrackKey == trackKey }
    }

    /**
     * What is known about one hire, from data already held.
     *
     * The GitHub-history half is **consent-gated and may simply be absent**; an empty prior means
     * *no evidence*, never "beginner" — the same rule the assessment interviewer follows, and for
     * the same reason: otherwise the feature meant to help new joiners punishes them.
     */
    private fun buildProfile(userId: UUID): StarterWorkMatcher.HireProfile {
        // Level 0 means "unknown/not yet placed" -- such a ledger row is not a competency the
        // hire has met, so it must not count toward fit.
        val competencyKeys = userCompetencyStateRepository
            .findAllByUserId(userId)
            .filter { it.level > 0 }
            .map { it.competencyKey }
            .toSet()

        // Null when the hire never consented, which is a legitimate and common state.
        val signals = githubHistoryPriorService.getPrior(userId)?.signals.orEmpty()
        val repositories = signals.keys
            .filter { it.startsWith(REPO_SIGNAL_PREFIX) }
            .map { it.removePrefix(REPO_SIGNAL_PREFIX) }
            .toSet()
        val labels = signals.keys
            .filter { it.startsWith(LABEL_SIGNAL_PREFIX) }
            .map { it.removePrefix(LABEL_SIGNAL_PREFIX).lowercase() }
            .toSet()

        return StarterWorkMatcher.HireProfile(
            competencyKeys = competencyKeys,
            familiarRepositories = repositories,
            familiarLabels = labels,
            // Derived from the same labels rather than stored separately, so a hire's task-type
            // familiarity and a task's type are always read off the same vocabulary.
            familiarTaskTypes = labels
                .mapNotNull { label ->
                    TaskType.fromLabels(listOf(label)).takeIf { it != TaskType.OTHER }
                }.toSet(),
        )
    }

    /**
     * The ranking features of one pool task.
     *
     * Labels and repository come from the **ingested issue**, not from the proposal row: mining
     * stored a title, a summary and competency tags, but the issue's own labels are what the
     * project actually says this work is. Falls back to no labels when the issue is no longer
     * ingested, which costs the task its type signal rather than inventing one.
     */
    private fun featuresOf(proposal: StarterWorkTaskProposal): StarterWorkMatcher.TaskFeatures {
        val source = artifactIngestionApi.getTaskSource(proposal.sourceId)
        val labels = source
            ?.labels
            .orEmpty()
            .map { it.lowercase() }
            .toSet()
        return StarterWorkMatcher.TaskFeatures(
            competencyKeys = proposal.competencyKeys.toSet(),
            taskType = TaskType.fromLabels(labels),
            labels = labels,
            repositoryFullName = repositoryOf(proposal.sourceId),
            reviewed = proposal.reviewed,
        )
    }

    /**
     * The `owner/name` a mined task's source id points at.
     *
     * Source ids are built by `SourceIdFactory` as `github:owner/name:TYPE:number`, so the
     * repository is already in the identifier and needs no extra lookup.
     */
    private fun repositoryOf(sourceId: String): String? =
        sourceId.split(':').getOrNull(1)?.takeIf { it.contains('/') }

    companion object {
        // Matches the AI service's operation name and the frontend consumer's switch.
        private const val STARTER_WORK = "starter_work"

        /** Namespaces used by `GithubHistoryPrior.signals`; see that entity's docs. */
        private const val REPO_SIGNAL_PREFIX = "repo:"
        private const val LABEL_SIGNAL_PREFIX = "label:"

        /**
         * Source-id namespace for a hand-authored task, deliberately not the `github:owner/name:...`
         * shape a mined task carries. [repositoryOf] finds no `owner/name` in it (so fit-ranking
         * attaches no repository-responsiveness signal) and `ArtifactIngestionApi.getTaskSource`
         * finds no ingested artifact -- both degrade to "no signal" rather than misattributing.
         */
        private const val HAND_AUTHORED_SOURCE_PREFIX = "authored:"

        /** How the ingestion mappers spell "finished at the source", for GitHub and Jira alike. */
        private const val CLOSED_STATE = "CLOSED"

        /**
         * How much of a browsed issue's body a list row carries.
         *
         * A browser over an organisation-sized corpus is thousands of rows, and an issue body is by
         * far the largest thing on one. Enough to judge scope, and the response says outright when
         * something was cut rather than letting a reader mistake a truncated body for a short one.
         */
        private const val CANDIDATE_EXCERPT_CHARS = 600
    }

    private fun findLiveProposal(id: UUID): StarterWorkTaskProposal {
        val proposal = starterWorkTaskProposalRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No starter-work task found with id: $id")
        }
        if (proposal.status != ProposalStatus.LIVE) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Starter-work task $id was rejected and is no longer in the pool",
            )
        }
        return proposal
    }
}
