package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.ProposalStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphChangeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
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
 * The path is scoped to a project: its target set is the competency keys declared by that
 * project's ACTIVE blueprint steps for the user's role scopes (the blueprint->target bridge).
 * Blueprint steps that carry no competency key yet (the AI does not emit them yet) contribute
 * nothing, so a project whose blueprints declare no keys falls back to every currently-visible
 * competency -- the previous behavior, kept so nothing breaks before keys are populated.
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
    private val blueprintRepository: BlueprintRepository,
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

        // First competency-to-step bridge in the codebase (#8): a competency key is expected to be
        // taught/verified by at most one step -- if more than one Verification shares a key,
        // `associateBy` keeps the last one encountered, an acceptable simplification until
        // graph-authoring (Phase 5) can enforce uniqueness.
        val verificationByCompetencyKey = verificationRepository
            .findAllByCompetencyKeyIn(effectiveGraph.competencies.map { it.key })
            .associateBy { it.competencyKey }

        // Echo the *pin's* version, not the live head: the projected content is the effective
        // graph at the pin (STRUCTURAL changes above it are held back), so echoing the head
        // would claim a version whose content isn't shown -- and clients diffing the version to
        // detect "your path changed" would fire while the change is still hidden, then miss the
        // session start where the pin advances and the content actually appears.
        val visibleKeys = effectiveGraph.competencies.map { it.key }.toSet()

        return pathProjectionService.project(
            competencies = effectiveGraph.competencies,
            edges = effectiveGraph.edges,
            targetKeys = resolveTargetKeys(userId, projectId, visibleKeys),
            ledger = ledger,
            graphVersion = pin.pinnedVersion,
            stepIdByCompetencyKey = verificationByCompetencyKey.mapValues { it.value.stepId },
            verificationTypeByCompetencyKey = verificationByCompetencyKey.mapValues { it.value.type },
        )
    }

    /**
     * Resolves the competency keys this project's path should terminate in -- the blueprint->target
     * bridge. Loads the project's ACTIVE blueprints for the user's role scopes (`global` +
     * `area:<role>`, project-own with a fallback to the unscoped blueprint), and collects the
     * non-rejected steps' declared competency keys, restricted to what is currently visible.
     *
     * Falls back to every visible competency when the project's blueprints declare no keys yet,
     * so behavior is unchanged until blueprint steps start carrying competency keys.
     */
    private fun resolveTargetKeys(userId: UUID, projectId: UUID, visibleKeys: Set<String>): Set<String> {
        val scopes = userApi
            .getProjectRolesForUser(userId, projectId)
            .map { "area:${it.toAiScope()}" }
            .toSet() + "global"

        val declaredKeys = scopes
            .mapNotNull { scope ->
                blueprintRepository.findByProjectIdAndScopeAndStatus(projectId, scope, BlueprintStatus.ACTIVE)
                    ?: blueprintRepository.findByProjectIdIsNullAndScopeAndStatus(scope, BlueprintStatus.ACTIVE)
            }.flatMap { blueprint -> blueprint.steps }
            .filter { it.status != ProposalStatus.REJECTED }
            .mapNotNull { it.competencyKey }
            .filter { it in visibleKeys }
            .toSet()

        return declaredKeys.ifEmpty { visibleKeys }
    }

    private fun resolvePin(userId: UUID, currentVersion: Int): UserGraphPin =
        userGraphPinRepository.findByUserId(userId)
            ?: userGraphPinRepository.save(UserGraphPin(userId = userId, pinnedVersion = currentVersion))

    private fun resolveUserId(authId: String) =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }
}
