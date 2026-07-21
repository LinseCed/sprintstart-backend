package com.sprintstart.sprintstartbackend.onboarding.external

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.ActiveEdgeSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AiProgressEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.ArtifactEvidenceDto
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssembleOrientationRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentHistoryEntrySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.AssessmentTurnResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.BaselineSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyStreamEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.BuddyStreamRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateBlueprintsRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateBlueprintsResponse
import com.sprintstart.sprintstartbackend.onboarding.external.model.GenerateCompetencyGraphRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeArtifactRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeKnowledgeRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.GradeResult
import com.sprintstart.sprintstartbackend.onboarding.external.model.GraphProposalOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.MineStarterWorkRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.ModuleProposalOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.OrientationOutcome
import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposeModuleRequest
import com.sprintstart.sprintstartbackend.onboarding.external.model.StarterWorkOutcome
import com.sprintstart.sprintstartbackend.onboarding.model.exceptions.OnboardingAiException
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import com.sprintstart.sprintstartbackend.shared.web.WebClientException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

// One method per AI-service endpoint: the count tracks the size of Seam 1, not a class doing too
// many things. Splitting it by endpoint group would hide that seam rather than shrink it.
@Suppress("TooManyFunctions")
@Component
class OnboardingAiClient(
    private val webClient: WebClient,
    private val applicationConfig: ApplicationConfig,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Runs the AI service's batch blueprint generation job over the ingested corpus.
     *
     * The AI service is stateless: [active] (the backend's current active baselines)
     * drives version numbering and lets the job skip an unchanged corpus. A non-2xx
     * response is wrapped in an [OnboardingAiException] carrying the upstream status/body.
     *
     * @param scopes The scopes to (re)generate, or `null` to refresh all known scopes.
     * @param active The backend's currently-active baselines for the requested scopes.
     * @param activeCompetencies The backend's live competency graph -- the set the AI selects the
     *   baseline from.
     * @return The per-scope generation outcomes returned by the AI service.
     */
    suspend fun generateBlueprints(
        scopes: List<String>?,
        active: List<BaselineSchema> = emptyList(),
        activeCompetencies: List<ActiveCompetencySchema> = emptyList(),
    ): GenerateBlueprintsResponse =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/blueprints/generate"))
                .body(
                    GenerateBlueprintsRequest(
                        scopes = scopes,
                        active = active,
                        activeCompetencies = activeCompetencies,
                    ),
                ).sync()
                .perform<GenerateBlueprintsResponse>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to generate blueprints (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Runs one turn of the stateless adaptive skill-assessment interview.
     *
     * The backend owns session state; [request] carries the full transcript so far. A non-2xx
     * response is wrapped in an [OnboardingAiException] carrying the upstream status/body.
     *
     * @param request The candidate competencies, repo signal, transcript, and turn/cap state.
     * @return Either the next question (`done=false`) or the final placement (`done=true`).
     */
    suspend fun assessTurn(request: AssessmentTurnRequest): AssessmentTurnResponse =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/assessment/turn"))
                .body(request)
                .sync()
                .perform<AssessmentTurnResponse>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to run assessment turn (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Runs the AI service's batch competency graph proposal job over the ingested corpus.
     *
     * The AI service is stateless: [activeCompetencies]/[activeEdges] (the backend's current
     * live graph) drive dedup, and [lastFingerprint] drives idempotency -- there is no
     * "active proposal" object on this side to carry it, unlike blueprint generation. A non-2xx
     * response is wrapped in an [OnboardingAiException] carrying the upstream status/body.
     *
     * @param activeCompetencies The backend's current live competency nodes.
     * @param activeEdges The backend's current live prerequisite edges.
     * @param lastFingerprint The corpus fingerprint recorded from the most recent prior proposal, if any.
     * @return The proposal outcome returned by the AI service.
     */
    suspend fun proposeCompetencyGraph(
        activeCompetencies: List<ActiveCompetencySchema> = emptyList(),
        activeEdges: List<ActiveEdgeSchema> = emptyList(),
        lastFingerprint: String? = null,
    ): GraphProposalOutcome =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/competency-graph/propose"))
                .body(
                    GenerateCompetencyGraphRequest(
                        activeCompetencies = activeCompetencies,
                        activeEdges = activeEdges,
                        lastFingerprint = lastFingerprint,
                    ),
                ).sync()
                .perform<GraphProposalOutcome>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to propose competency graph (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Proposes the shared module one competency teaches (#49/ai#19).
     *
     * Heavyweight/offline, matching [synthesizeLesson]: one retrieval + LLM pass, intended for an
     * authoring action rather than a hire's request path. Nothing about an individual hire is sent
     * -- one competency yields one module everybody reads. [lastFingerprint] is whatever
     * fingerprint the caller last recorded for this competency, so an unchanged corpus does not
     * churn a module a PM has already edited.
     *
     * @param competencyKey The competency this module teaches.
     * @param competencyLabel The competency's display label.
     * @param competencyDescription Optional extra context for grounding the pages.
     * @param level Target level to teach to (`beginner`/`intermediate`/`advanced`/`expert`).
     * @param lastFingerprint The corpus fingerprint recorded from the last proposal, if any.
     * @return The proposal outcome returned by the AI service.
     */
    suspend fun proposeModule(
        competencyKey: String,
        competencyLabel: String,
        competencyDescription: String = "",
        level: String = "beginner",
        lastFingerprint: String? = null,
    ): ModuleProposalOutcome =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/modules/propose"))
                .body(
                    ProposeModuleRequest(
                        competencyKey = competencyKey,
                        competencyLabel = competencyLabel,
                        competencyDescription = competencyDescription,
                        level = level,
                        lastFingerprint = lastFingerprint,
                    ),
                ).sync()
                .perform<ModuleProposalOutcome>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to propose module (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Assembles the orientation packet for one task from the project's existing material (#73/ai#31).
     *
     * Unlike [proposeModule] this *is* on a hire's request path â€” it is what somebody reads while
     * doing the task they just picked up â€” which is why the caller caches the result against the
     * task and sends [lastFingerprint] on every read: an unchanged corpus comes back `unchanged`
     * with no retrieval or LLM pass, and a corpus that has moved is re-assembled rather than
     * described from a packet that no longer matches the code.
     *
     * Nothing about the individual hire is sent, deliberately: orientation is a property of the
     * task, so two people who claim it read the same packet and can talk about it.
     *
     * The AI service returns `skipped` with no packet when it cannot ground one. That is a real
     * answer and must reach the hire as an honest empty state â€” never a fabricated packet.
     *
     * @param taskTitle The task the packet orients somebody for.
     * @param taskBody The task's description, when it has one.
     * @param labels The task's labels, used to aim retrieval.
     * @param touchedPaths Repository paths the task is expected to touch, when known.
     * @param lastFingerprint The corpus fingerprint recorded when this task's packet was last
     *   assembled, if any.
     * @return The assembly outcome returned by the AI service.
     */
    suspend fun assembleOrientation(
        taskTitle: String,
        taskBody: String = "",
        labels: List<String> = emptyList(),
        touchedPaths: List<String> = emptyList(),
        lastFingerprint: String? = null,
    ): OrientationOutcome =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/orientation"))
                .body(
                    AssembleOrientationRequest(
                        taskTitle = taskTitle,
                        taskBody = taskBody,
                        labels = labels,
                        touchedPaths = touchedPaths,
                        lastFingerprint = lastFingerprint,
                    ),
                ).sync()
                .perform<OrientationOutcome>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to assemble orientation (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Grades a free-text answer against a rubric via the AI service's LLM judge (Phase 3, #8).
     *
     * On the learner's request path, unlike [synthesizeLesson] -- called synchronously per
     * verification attempt. Only `knowledge`-type grading is delegated to the AI service;
     * `exact`/`attest` are graded locally in Kotlin (see `VerificationService`), so this method has
     * no `type` parameter. A non-2xx response is wrapped in an [OnboardingAiException] carrying the
     * upstream status/body -- callers should treat that as a retryable failure, not a graded fail,
     * since there is no safe local fallback for rubric-based judging.
     *
     * @param question The verification prompt shown to the learner.
     * @param rubric What a correct answer must demonstrate.
     * @param evidence Grounded evidence backing the rubric (the step's lesson content).
     * @param answer The learner's submitted answer.
     * @param attemptNo The 1-based attempt number, steering hint escalation on fail.
     * @return The grading result returned by the AI service.
     */
    suspend fun gradeKnowledge(
        question: String,
        rubric: String,
        evidence: String,
        answer: String,
        attemptNo: Int,
    ): GradeResult =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/verify"))
                .body(
                    GradeKnowledgeRequest(
                        question = question,
                        answer = answer,
                        attemptNo = attemptNo,
                        rubric = rubric,
                        evidence = evidence,
                    ),
                ).sync()
                .perform<GradeResult>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to grade knowledge answer (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Grades a hire-submitted PR's real state against a rubric via the AI service's LLM judge
     * (Phase 4, #9), the highest-rigor rung of the verification ladder.
     *
     * On the learner's request path, exactly like [gradeKnowledge] -- called synchronously per
     * verification attempt, after the backend has already gathered [evidence] from GitHub itself
     * (see `VerificationService.gradeArtifact`, which sources it via `GithubRepositoryApi`). The AI
     * service never re-derives facts like merge/CI status; it only judges whether the evidence's
     * content satisfies the rubric, and already short-circuits to a fail with no LLM call when
     * there's no evidence or CI is explicitly failing. A non-2xx response is wrapped in an
     * [OnboardingAiException] carrying the upstream status/body, same retryable-failure contract as
     * [gradeKnowledge].
     *
     * @param taskDescription The verification prompt describing what the PR must accomplish.
     * @param rubric What the PR's real state must demonstrate.
     * @param evidence The backend-gathered PR/repo state.
     * @return The grading result returned by the AI service.
     */
    suspend fun gradeArtifact(
        taskDescription: String,
        rubric: String,
        evidence: ArtifactEvidenceDto,
    ): GradeResult =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/verify"))
                .body(
                    GradeArtifactRequest(
                        question = taskDescription,
                        rubric = rubric,
                        artifactEvidence = evidence,
                    ),
                ).sync()
                .perform<GradeResult>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to grade artifact evidence (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    /**
     * Opens an SSE stream against the AI service's persistent-buddy endpoint (Phase 5, ai#6).
     *
     * Stateless like every other onboarding endpoint: the caller (backend) owns conversation
     * history and passes the full transcript on every call, same contract as [assessTurn]. Each
     * emitted [BuddyStreamEvent] has already been interpreted for type -- `token`, `citation`, and
     * `tool_use` chunks pass through; `done` terminates the stream normally; an `error` chunk
     * terminates the stream with [OnboardingAiException] (status `502`, since this is an in-band
     * stream failure reported by the AI service, not a rejected HTTP request).
     *
     * @param question The hire's message.
     * @param history The conversation so far, oldest first.
     * @return A cold [Flow] of [BuddyStreamEvent]s; the connection opens on collection.
     */
    fun streamBuddy(
        question: String,
        history: List<AssessmentHistoryEntrySchema> = emptyList(),
    ): Flow<BuddyStreamEvent> =
        webClient
            .post()
            .uri(uri("/api/v1/onboarding/buddy"))
            .body(BuddyStreamRequest(question = question, history = history))
            .stream()
            .perform<BuddyStreamEvent>(
                terminationMarkers = setOf("[DONE]"),
                onChunkError = { raw, err ->
                    logger.warn("Skipping malformed SSE chunk '{}': {}", raw, err.message)
                    true
                },
            ).map { chunk ->
                if (chunk.type == "error") {
                    throw OnboardingAiException(
                        BUDDY_STREAM_ERROR_STATUS,
                        chunk.message ?: "",
                        "AI buddy responded with error: ${chunk.message}",
                    )
                }
                chunk
            }

    /**
     * Runs the AI service's batch starter-work mining job over the ingested corpus (Phase 4, #9).
     *
     * The AI service is stateless: [activeSourceIds] (issues already in the backend's pool,
     * proposed or approved) drive dedup, and [activeCompetencyKeys] (the backend's live graph
     * keys) ground each proposed task's competency tags -- a tag outside this set is dropped by
     * the AI service rather than invented. A non-2xx response is wrapped in an
     * [OnboardingAiException] carrying the upstream status/body.
     *
     * @param activeSourceIds Issues already in the backend's starter-work pool.
     * @param activeCompetencyKeys The backend's current live competency graph keys.
     * @return The mining outcome returned by the AI service.
     */
    suspend fun proposeStarterWork(
        activeSourceIds: List<String> = emptyList(),
        activeCompetencyKeys: List<String> = emptyList(),
    ): StarterWorkOutcome =
        try {
            webClient
                .post()
                .uri(uri("/api/v1/onboarding/starter-work/mine"))
                .body(
                    MineStarterWorkRequest(
                        activeSourceIds = activeSourceIds,
                        activeCompetencyKeys = activeCompetencyKeys,
                    ),
                ).sync()
                .perform<StarterWorkOutcome>()
        } catch (@Suppress("SwallowedException") e: WebClientException) {
            val msg = "Failed to propose starter work (HTTP ${e.statusCode}): ${e.body}"
            throw OnboardingAiException(e.statusCode, e.body, msg)
        }

    // `matchHireToPool` used to live here, calling the AI service's /starter-work/match. It is gone
    // rather than left unused: #74 requires that a hire be told why a task was suggested, and an
    // embedding tie-break cannot say -- so ranking moved into `StarterWorkMatcher`, which is
    // deterministic, local and self-explaining. The AI service's /match endpoint now has no caller
    // and is a candidate for removal in slice 5.

    /**
     * Streams the AI service assembling an orientation packet (live-AI-visibility, #94/ai#36).
     *
     * The streaming twin of [assembleOrientation]: same inputs and same result, but the AI emits
     * [AiProgressEvent]s as it works (a `stage` per retrieval step, an `item` per grounded section,
     * a terminal `done` carrying the whole outcome). Unlike [streamBuddy] an `error` chunk is *not*
     * turned into an exception -- it is a terminal event the caller relays to the browser, so a hire
     * watching the assembly sees it fail rather than the connection dropping. The persisted packet
     * is taken from the `done` event's `result`, so it is byte-for-byte what the cached call returns.
     */
    fun streamOrientation(
        taskTitle: String,
        taskBody: String = "",
        labels: List<String> = emptyList(),
        touchedPaths: List<String> = emptyList(),
        lastFingerprint: String? = null,
    ): Flow<AiProgressEvent> =
        streamProgress(
            "/api/v1/onboarding/orientation/stream",
            AssembleOrientationRequest(
                taskTitle = taskTitle,
                taskBody = taskBody,
                labels = labels,
                touchedPaths = touchedPaths,
                lastFingerprint = lastFingerprint,
            ),
        )

    /**
     * Streams the AI service proposing a competency module (live-AI-visibility, #94/ai#36).
     *
     * The streaming twin of [proposeModule]: the AI emits `stage`/`item`/`warning` events as it
     * writes and grounds pages, and a terminal `done` carrying the outcome the backend persists.
     */
    fun streamModule(
        competencyKey: String,
        competencyLabel: String,
        competencyDescription: String = "",
        level: String = "beginner",
        lastFingerprint: String? = null,
    ): Flow<AiProgressEvent> =
        streamProgress(
            "/api/v1/onboarding/modules/propose/stream",
            ProposeModuleRequest(
                competencyKey = competencyKey,
                competencyLabel = competencyLabel,
                competencyDescription = competencyDescription,
                level = level,
                lastFingerprint = lastFingerprint,
            ),
        )

    /**
     * Opens an SSE stream of [AiProgressEvent]s against [path], POSTing [body].
     *
     * The reusable passthrough behind every streaming operation. A malformed chunk is logged and
     * skipped (never kills the stream); the AI's own terminal `error` event passes straight through,
     * because progress errors are shown, not thrown.
     */
    private inline fun <reified B> streamProgress(path: String, body: B): Flow<AiProgressEvent> =
        webClient
            .post()
            .uri(uri(path))
            .body(body)
            .stream()
            .perform<AiProgressEvent>(
                onChunkError = { raw, err ->
                    logger.warn("Skipping malformed AI progress chunk '{}': {}", raw, err.message)
                    true
                },
            )

    /** Builds an absolute URI for [path] against the configured AI service base URL. */
    private fun uri(path: String): URI = URI.create("${applicationConfig.ai.baseUrl}$path")

    private companion object {
        const val BUDDY_STREAM_ERROR_STATUS = 502
    }
}
