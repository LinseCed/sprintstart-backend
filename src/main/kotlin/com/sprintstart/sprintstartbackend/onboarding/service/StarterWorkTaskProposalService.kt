package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.ingestion.external.ArtifactIngestionApi
import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.TaskType
import com.sprintstart.sprintstartbackend.onboarding.external.model.AiProgressEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.StarterWorkOutcome
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.request.starterwork.CreateStarterWorkTaskRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.GenerateStarterWorkResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.ProposedStarterWorkResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.RankedStarterWorkTaskResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkTaskProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
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
 * Mirrors [CompetencyProposalService]'s proposal-only relationship with the AI service, adapted
 * to a single AI-mined artifact per row instead of a node/edge pair (see
 * [StarterWorkTaskProposal]). Approving a proposal is this module's first producer of
 * `CONTRIBUTION`-kind [Competency] nodes -- the graph's terminal/goal-node kind -- wiring in
 * `PREREQUISITE` edges from every tagged competency key so the task becomes a reachable goal once
 * a hire has built the skills it requires.
 */
@Service
@Suppress("TooManyFunctions")
class StarterWorkTaskProposalService(
    private val onboardingAiClient: OnboardingAiClient,
    private val competencyRepository: CompetencyRepository,
    private val competencyEdgeRepository: CompetencyEdgeRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val competencyGraphVersionService: CompetencyGraphVersionService,
    private val githubHistoryPriorService: GithubHistoryPriorService,
    private val artifactIngestionApi: ArtifactIngestionApi,
    private val userApi: UserApi,
    private val json: Json,
    transactionManager: PlatformTransactionManager,
) {
    // The AI call is a long-running suspend operation, so it must not run inside a
    // transaction (a DB connection would be pinned for its whole duration) -- same
    // reasoning as CompetencyProposalService.
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Triggers AI starter-work mining and persists the results as PROPOSED.
     *
     * Issues already in the pool (PROPOSED or APPROVED -- not REJECTED, so a PM's rejection
     * doesn't permanently block re-mining if circumstances change) and the current live
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
     * The streaming twin of [generate] (live-AI-visibility, #95/ai#37).
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
            .findAllByStatusIn(listOf(ProposalStatus.PROPOSED, ProposalStatus.APPROVED))
            .map { it.sourceId }
        val competencyKeys = competencyRepository.findAll().map { it.key }
        return ActiveState(sourceIds, competencyKeys)
    }

    /**
     * Persists the outcome's tasks as PROPOSED rows, re-applying the backend's own gate: a task
     * whose issue is already in the pool (PROPOSED or APPROVED) is not re-proposed. The AI dedupes
     * against the active pool the caller sends, but this is the authoritative check at write time —
     * and it closes the window where a second mining run before a PM decides could double-pool an
     * issue. Returns how many landed and a human note per skipped task, so the streaming path can
     * surface each skip as a `warning`.
     */
    private fun persistProposals(outcome: StarterWorkOutcome): PersistResult {
        val alreadyPooled = starterWorkTaskProposalRepository
            .findAllByStatusIn(listOf(ProposalStatus.PROPOSED, ProposalStatus.APPROVED))
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
    fun listProposed(): ProposedStarterWorkResponse =
        ProposedStarterWorkResponse(
            tasks = starterWorkTaskProposalRepository
                .findAllByStatus(ProposalStatus.PROPOSED)
                .map { it.toResponse() },
        )

    /**
     * Returns the approved starter-work pool, for a PM choosing a task to author orientation for.
     *
     * The whole approved set, ordered by title, not scoped to a project: approved tasks are a global
     * pool (the entity has no `projectId`), and orientation is per-`(task, project)` only because the
     * corpus a packet is grounded in is per-project.
     */
    @Transactional(readOnly = true)
    fun listApproved(): List<StarterWorkTaskProposalResponse> =
        starterWorkTaskProposalRepository
            .findAllByStatus(ProposalStatus.APPROVED)
            .sortedBy { it.title }
            .map { it.toResponse() }

    /**
     * Approves a proposed starter-work task, creating a real `CONTRIBUTION` [Competency] and
     * wiring `PREREQUISITE` edges from each of its tagged competency keys.
     *
     * A tagged key that isn't (yet) a live competency is skipped rather than blocking the whole
     * approval -- unlike [CompetencyProposalService.approveEdge]'s hard 409, since a starter-work
     * task's competency tags are enrichment, not the thing being approved.
     *
     * @throws ResponseStatusException 404 if no PROPOSED task proposal matches [id].
     */
    @Transactional
    fun approve(id: UUID): StarterWorkTaskProposalResponse {
        val proposal = findPendingProposal(id)
        materialiseContributionNode(proposal)
        proposal.status = ProposalStatus.APPROVED
        proposal.decidedAt = Instant.now()
        return proposal.toResponse()
    }

    /**
     * Creates a hand-authored starter-work task, with no AI mining in the loop.
     *
     * Born `APPROVED` and materialised straight away: a PM authoring a task is the review, so there
     * is nothing to approve back to them (the same reasoning as direct baseline authoring). The task
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
                status = ProposalStatus.APPROVED,
                decidedAt = Instant.now(),
            ),
        )
        materialiseContributionNode(proposal)
        return proposal.toResponse()
    }

    /**
     * Creates the `CONTRIBUTION` [Competency] a task becomes and wires a `PREREQUISITE` edge from
     * each tagged key that is a live competency, then bumps the graph version.
     *
     * Shared by [approve] and [createTask] so a mined and a hand-authored task land in the graph
     * identically. A tagged key that isn't a live competency is skipped rather than blocking, and an
     * already-present node or edge is left alone -- both paths are idempotent w.r.t. the graph.
     */
    private fun materialiseContributionNode(proposal: StarterWorkTaskProposal) {
        val contributionKey = contributionKeyFor(proposal.sourceId)

        if (!competencyRepository.existsByKey(contributionKey)) {
            competencyRepository.save(
                Competency(
                    key = contributionKey,
                    label = proposal.title,
                    description = proposal.summary,
                    kind = CompetencyKind.CONTRIBUTION,
                    repoRef = proposal.sourceUrl,
                ),
            )
            competencyGraphVersionService.recordNodeAdded(contributionKey)
        }

        val liveKeys = competencyRepository.findAllByKeyIn(proposal.competencyKeys).map { it.key }.toSet()
        proposal.competencyKeys
            .filter { it in liveKeys }
            .forEach { prereqKey ->
                if (!competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(
                        prereqKey,
                        contributionKey,
                        EdgeKind.PREREQUISITE,
                    )
                ) {
                    competencyEdgeRepository.save(
                        CompetencyEdge(fromKey = prereqKey, toKey = contributionKey, kind = EdgeKind.PREREQUISITE),
                    )
                    competencyGraphVersionService.recordEdgeAdded(prereqKey, contributionKey, EdgeKind.PREREQUISITE)
                }
            }

        competencyGraphVersionService.bump()
    }

    /**
     * Rejects a proposed starter-work task, archiving it without touching the live graph.
     *
     * @throws ResponseStatusException 404 if no PROPOSED task proposal matches [id].
     */
    @Transactional
    fun reject(id: UUID, reason: String?): StarterWorkTaskProposalResponse {
        val proposal = findPendingProposal(id)
        proposal.status = ProposalStatus.REJECTED
        proposal.decidedAt = Instant.now()
        proposal.rejectionReason = reason
        return proposal.toResponse()
    }

    /**
     * Ranks the current (APPROVED) starter-work pool by fit for the authenticated user on a project.
     *
     * **No longer an AI call.** Ranking used to delegate to the AI service, which scored competency
     * overlap and broke ties on embeddings; #74 requires that a hire be told in one line why a task
     * was suggested, and an embedding distance cannot say. The ranking is now deterministic, local
     * and self-explaining (see [StarterWorkMatcher]) — which also takes a network round trip off a
     * hire's request path.
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
        val pool = starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED)
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

        /**
         * Derives a stable, deterministic competency key for the CONTRIBUTION node an approved
         * task becomes.
         *
         * Deliberately a pure function of [sourceId] and nothing else, so the mapping from task to
         * node survives everything a PM can change: since graph authoring (#50) a node's label,
         * description and kind are all editable, so matching a task to its node on any of those
         * would break the first time somebody renamed it.
         */
        fun contributionKeyFor(sourceId: String): String =
            sourceId.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    private fun findPendingProposal(id: UUID): StarterWorkTaskProposal {
        val proposal = starterWorkTaskProposalRepository.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No starter-work task proposal found with id: $id")
        }
        if (proposal.status != ProposalStatus.PROPOSED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Starter-work task proposal $id is already ${proposal.status}",
            )
        }
        return proposal
    }
}
