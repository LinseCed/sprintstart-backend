package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.UserGraphPin
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphChangeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
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
 * ADDITIVE/INVARIANT changes stay visible immediately. There is no bridge yet from
 * `BlueprintStep` to `Competency.key` (that's future work), so the target set is every
 * currently-visible competency -- documented simplification, not a guess.
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
    private val userApi: UserApi,
) {
    @Transactional
    fun getPathForMe(authId: String): PathView {
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

        return pathProjectionService.project(
            competencies = effectiveGraph.competencies,
            edges = effectiveGraph.edges,
            targetKeys = effectiveGraph.competencies.map { it.key }.toSet(),
            ledger = ledger,
            graphVersion = currentVersion,
            stepIdByCompetencyKey = verificationByCompetencyKey.mapValues { it.value.stepId },
            verificationTypeByCompetencyKey = verificationByCompetencyKey.mapValues { it.value.type },
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
