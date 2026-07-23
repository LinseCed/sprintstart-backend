package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModuleStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGoal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GoalView
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphChangeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyModuleRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.StarterWorkTaskProposalRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserGraphPinRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.VerificationRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Orchestrates [PathProjectionService.project] for `GET /me/path`: resolves the authenticated
 * user, loads the graph content currently visible to them, and projects their personalized path.
 *
 * "Visible to them" is [EffectiveGraphResolver]'s job, not the full live graph: a hire's
 * [UserGraphPin] holds back STRUCTURAL changes until their next session start while
 * ADDITIVE/INVARIANT changes stay visible immediately.
 *
 * The path is scoped to a project and is **goal-directed**: it aims at the hire's claimed **goal**
 * (the contribution they are working toward) and its transitive prerequisites — nothing else. There
 * is no PM-mandated baseline: since the hire drives onboarding through the buddy, task-first, the
 * plan is the road to the task they picked, not a curated competency set.
 *
 * Nothing falls back to "every visible competency": a hire who has claimed no goal gets an empty
 * path, which is the truth and visible as such — the buddy leans on suggested tasks and modules
 * until a goal is claimed.
 */
@Service
class CompetencyPathService(
    private val competencyRepository: CompetencyRepository,
    private val competencyEdgeRepository: CompetencyEdgeRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val pathProjectionService: PathProjectionService,
    private val competencyGraphVersionService: CompetencyGraphVersionService,
    private val competencyGraphVersionRepository: CompetencyGraphVersionRepository,
    private val competencyGraphChangeRepository: CompetencyGraphChangeRepository,
    private val userGraphPinRepository: UserGraphPinRepository,
    private val effectiveGraphResolver: EffectiveGraphResolver,
    private val verificationRepository: VerificationRepository,
    private val competencyModuleRepository: CompetencyModuleRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val userGoalService: UserGoalService,
    private val graphTraversalService: GraphTraversalService,
    private val userApi: UserApi,
) {
    @Transactional
    fun getPathForMe(authId: String, projectId: UUID): PathView =
        getPathForUser(resolveUserId(authId), projectId)

    /**
     * [getPathForMe] for a caller that already holds the resolved user id — the buddy's tools act
     * on behalf of a hire they have already identified, and must never resolve somebody else's.
     */
    @Transactional
    fun getPathForUser(userId: UUID, projectId: UUID): PathView {
        val currentVersion = competencyGraphVersionService.currentVersion()
        val pin = resolvePin(userId, currentVersion)

        val effectiveGraph = effectiveGraphResolver.resolve(
            pinnedVersion = pin.pinnedVersion,
            currentVersion = currentVersion,
            versionHistory = competencyGraphVersionRepository
                .findAllByVersionGreaterThanOrderByVersionAsc(pin.pinnedVersion),
            changes = competencyGraphChangeRepository.findAll(),
            allCompetencies = competencyRepository.findAll(),
            allEdges = competencyEdgeRepository.findAll(),
        )

        val ledger = userCompetencyStateRepository
            .findAllByUserId(userId)
            .associate { it.competencyKey to it.level }

        // What a hire opens when they click a node: the live module for that competency in this
        // project. One per (competency, project) by construction -- the module is the shared
        // artifact, so there is no per-user copy to disambiguate between.
        val liveModules = competencyModuleRepository
            .findAllByProjectIdAndStatus(projectId, ModuleStatus.ACTIVE)
            .associateBy { it.competencyKey }
        val competencyKeyByModuleId = liveModules.entries.associate { (key, module) -> module.id to key }
        val checkTypeByCompetencyKey = verificationRepository
            .findAllByModuleIdIn(competencyKeyByModuleId.keys)
            .mapNotNull { check -> competencyKeyByModuleId[check.moduleId]?.let { it to check.type } }
            .toMap()

        // Echo the *pin's* version, not the live head: the projected content is the effective
        // graph at the pin (STRUCTURAL changes above it are held back), so echoing the head
        // would claim a version whose content isn't shown -- and clients diffing the version to
        // detect "your path changed" would fire while the change is still hidden, then miss the
        // session start where the pin advances and the content actually appears.
        val visibleKeys = effectiveGraph.competencies.map { it.key }.toSet()
        val goal = userGoalService.findForUser(userId, projectId, visibleKeys)

        // Goal-directed: the path aims at the contribution the hire has claimed and its
        // prerequisite chain -- nothing else. There is no PM baseline anymore. Since the hire drives
        // onboarding through the buddy, task-first, the plan is the road to the task they picked,
        // not a curated competency set the team mandates. No goal claimed yet => an empty path, and
        // the buddy leans on suggested tasks and modules instead -- which is the honest state.
        val targetKeys = setOfNotNull(goal?.competencyKey)

        val path = pathProjectionService.project(
            competencies = effectiveGraph.competencies,
            edges = effectiveGraph.edges,
            targetKeys = targetKeys,
            targetLevelOverrides = emptyMap(),
            ledger = ledger,
            graphVersion = pin.pinnedVersion,
            moduleIdByCompetencyKey = liveModules.mapValues { it.value.id },
            verificationTypeByCompetencyKey = checkTypeByCompetencyKey,
        )

        return path.copy(goal = goal?.let { describeGoal(it, path, effectiveGraph.edges) })
    }

    /**
     * Describes the claimed goal for the payload, including how much of *its own* chain is left.
     *
     * "Remaining" counts only this goal's unmet transitive prerequisites — which, now that the path
     * is goal-directed, is the whole path anyway; the count stays scoped to the goal's own chain so
     * it keeps reading as "how far to shipping this" even if the path ever carries more than one goal.
     */
    private fun describeGoal(goal: UserGoal, path: PathView, edges: List<CompetencyEdge>): GoalView {
        val stateByKey = path.nodes.associate { it.key to it.state }
        val blocking = graphTraversalService
            .transitivePrerequisites(goal.competencyKey, edges)
            .filter { stateByKey[it] != null && stateByKey[it] != NodeState.MASTERED }

        val proposal = goal.sourceProposalId?.let { starterWorkTaskProposalRepository.findById(it).orElse(null) }
        val node = path.nodes.firstOrNull { it.key == goal.competencyKey }

        return GoalView(
            competencyKey = goal.competencyKey,
            label = node?.label ?: goal.competencyKey,
            summary = proposal?.summary,
            sourceUrl = proposal?.sourceUrl,
            sourceProposalId = goal.sourceProposalId,
            remainingCount = blocking.size,
            isReachable = blocking.isEmpty(),
        )
    }

    private fun resolvePin(userId: UUID, currentVersion: Int): UserGraphPin =
        userGraphPinRepository.findByUserId(userId)
            ?: userGraphPinRepository.save(UserGraphPin(userId = userId, pinnedVersion = currentVersion))

    private fun resolveUserId(authId: String) =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }
}
