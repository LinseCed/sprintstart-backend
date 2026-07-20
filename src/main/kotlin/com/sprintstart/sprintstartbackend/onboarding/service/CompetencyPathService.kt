package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ModuleStatus
import com.sprintstart.sprintstartbackend.onboarding.external.enums.NodeState
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGoal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GoalView
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
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
import com.sprintstart.sprintstartbackend.user.external.dto.toAiScope
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
 * The path is scoped to a project, and it aims at two things at once (backend#51):
 *
 * - the **baseline** -- the competency selection of that project's ACTIVE blueprints for the
 *   user's role scopes, which is the PM's mandatory floor for everybody;
 * - the hire's claimed **goal**, the contribution they are working toward.
 *
 * Targets are the *union*, so the path is the goal's prerequisite chain plus what the team
 * requires of everyone. Aiming only at the goal would silently drop invariant/compliance nodes a
 * PM mandated; aiming only at the baseline is what made a path an undirected slice of the graph.
 *
 * Nothing falls back to "every visible competency": a project with an empty baseline and no
 * claimed goal gets an empty path, which is the truth and visible as such.
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
    private val blueprintRepository: BlueprintRepository,
    private val starterWorkTaskProposalRepository: StarterWorkTaskProposalRepository,
    private val userGoalService: UserGoalService,
    private val graphTraversalService: GraphTraversalService,
    private val userApi: UserApi,
) {
    @Transactional
    fun getPathForMe(authId: String, projectId: UUID): PathView {
        val userId = resolveUserId(authId)
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
        val baseline = resolveBaseline(userId, projectId, visibleKeys)
        val goal = userGoalService.findForUser(userId, projectId, visibleKeys)

        // Union, not replacement. The baseline is the PM's mandatory floor -- including any
        // invariant/compliance node they approved for everyone -- so aiming at a contribution
        // must *add* that contribution's prerequisite chain, never quietly drop what the team
        // requires of everybody. The goal is what makes the path directed; the baseline is what
        // keeps it honest.
        val targetKeys = baseline.targetKeys + setOfNotNull(goal?.competencyKey)

        val path = pathProjectionService.project(
            competencies = effectiveGraph.competencies,
            edges = effectiveGraph.edges,
            targetKeys = targetKeys,
            targetLevelOverrides = baseline.targetLevelOverrides,
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
     * "Remaining" counts only this goal's unmet transitive prerequisites, not every unfinished
     * node on the path: a baseline node the team requires of everyone is real work, but it is not
     * what stands between this hire and shipping this contribution, and counting it would make
     * progress toward the destination unreadable.
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

    /**
     * Resolves this project's baseline: the competency keys the path should terminate in, and any
     * per-baseline level overrides. Loads the project's ACTIVE blueprints for the user's role
     * scopes (`global` + `area:<role>`, project-own with a fallback to the unscoped blueprint) and
     * reads their selections directly, restricted to what is currently visible.
     *
     * There is no fallback to "every visible competency": a path aims at what the PM selected. A
     * project with an empty baseline gets an empty path — which is the truth, and visible as such,
     * rather than a path spanning the whole graph that only looks like a plan.
     *
     * When two scopes select the same competency with different bars, the higher bar wins: a
     * role-specific baseline may raise what `global` asks for, never quietly lower it.
     */
    private fun resolveBaseline(userId: UUID, projectId: UUID, visibleKeys: Set<String>): Baseline {
        val scopes = userApi
            .getProjectRolesForUser(userId, projectId)
            .map { "area:${it.toAiScope()}" }
            .toSet() + "global"

        val entries = scopes
            .mapNotNull { scope ->
                blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, scope, BlueprintStatus.ACTIVE)
                    ?: blueprintRepository.findByProjectIdIsNullAndScopeAndStatus(scope, BlueprintStatus.ACTIVE)
            }.flatMap { blueprint -> blueprint.activeCompetencies() }
            .filter { it.competencyKey in visibleKeys }

        return Baseline(
            targetKeys = entries.map { it.competencyKey }.toSet(),
            targetLevelOverrides = entries
                .mapNotNull { entry -> entry.targetLevel?.let { entry.competencyKey to it } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, levels) -> levels.max() },
        )
    }

    /** A project's resolved baseline: what the path aims at, and the bars this project sets. */
    private data class Baseline(
        val targetKeys: Set<String>,
        val targetLevelOverrides: Map<String, Int>,
    )

    private fun resolvePin(userId: UUID, currentVersion: Int): UserGraphPin =
        userGraphPinRepository.findByUserId(userId)
            ?: userGraphPinRepository.save(UserGraphPin(userId = userId, pinnedVersion = currentVersion))

    private fun resolveUserId(authId: String) =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }
}
