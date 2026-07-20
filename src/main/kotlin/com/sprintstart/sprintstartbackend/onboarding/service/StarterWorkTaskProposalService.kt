package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.external.model.HireCompetencySchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.StarterWorkOutcome
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toSchema
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
import kotlinx.coroutines.withContext
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
class StarterWorkTaskProposalService(
    private val onboardingAiClient: OnboardingAiClient,
    private val competencyRepository: CompetencyRepository,
    private val competencyEdgeRepository: CompetencyEdgeRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val competencyGraphVersionService: CompetencyGraphVersionService,
    private val userApi: UserApi,
    transactionManager: PlatformTransactionManager,
) {
    // The AI call is a long-running suspend operation, so it must not run inside a
    // transaction (a DB connection would be pinned for its whole duration) -- same
    // reasoning as CompetencyProposalService.
    private val txTemplate = TransactionTemplate(transactionManager)
    private val readTxTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = true }

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
        withContext(Dispatchers.IO) {
            txTemplate.executeWithoutResult { persistProposals(outcome) }
        }
        return GenerateStarterWorkResponse(
            status = outcome.status,
            tasksProposed = outcome.tasks.size,
            notes = outcome.notes,
        )
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

    private fun persistProposals(outcome: StarterWorkOutcome) {
        outcome.tasks.forEach { proposed ->
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
        }
    }

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
        proposal.status = ProposalStatus.APPROVED
        proposal.decidedAt = Instant.now()
        return proposal.toResponse()
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
     * Ranks the current (APPROVED) starter-work pool by fit against the authenticated user's
     * ledger competencies.
     *
     * No claiming/assignment state is tracked here -- this is a ranking view only, matching issue
     * #9's scope ("match a hire -> task by the competencies they just built"). The AI call runs
     * outside any transaction.
     *
     * @throws ResponseStatusException 404 if no user matches [authId].
     */
    suspend fun matchForUser(authId: String): List<RankedStarterWorkTaskResponse> {
        val (hireCompetencies, pool) = withContext(Dispatchers.IO) {
            readTxTemplate.execute { loadMatchInput(authId) }!!
        }
        if (pool.isEmpty()) return emptyList()

        val ranked = onboardingAiClient.matchHireToPool(hireCompetencies, pool.map { it.toSchema() })
        val poolBySourceId = pool.associateBy { it.sourceId }
        return ranked.mapNotNull { rankedTask ->
            val proposal = poolBySourceId[rankedTask.task.sourceId] ?: return@mapNotNull null
            RankedStarterWorkTaskResponse(
                task = proposal.toResponse(),
                score = rankedTask.score,
                matchedCompetencyKeys = rankedTask.matchedCompetencyKeys,
            )
        }
    }

    private data class MatchInput(
        val hireCompetencies: List<HireCompetencySchema>,
        val pool: List<StarterWorkTaskProposal>,
    )

    private fun loadMatchInput(authId: String): MatchInput {
        val userId = userApi.getUserIdByAuthId(authId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId")
        }
        // Level 0 means "unknown/not yet placed" -- such a ledger row is not a competency the
        // hire has met, so it must not count toward fit.
        val ledger = userCompetencyStateRepository.findAllByUserId(userId).filter { it.level > 0 }
        val competenciesByKey = competencyRepository
            .findAllByKeyIn(ledger.map { it.competencyKey })
            .associateBy { it.key }
        val hireCompetencies = ledger.mapNotNull { state ->
            competenciesByKey[state.competencyKey]?.let {
                HireCompetencySchema(key = it.key, label = it.label, description = it.description ?: "")
            }
        }
        val pool = starterWorkTaskProposalRepository.findAllByStatus(ProposalStatus.APPROVED)
        return MatchInput(hireCompetencies, pool)
    }

    companion object {
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
