package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * Orchestrates [PathProjectionService.project] for `GET /me/path`: resolves the authenticated
 * user, loads the graph and their ledger, and projects their personalized path.
 *
 * There is no bridge yet from `BlueprintStep` to `Competency.key` (that's future work), so every
 * user's target is the full known graph for now -- documented simplification, not a guess.
 */
@Service
class CompetencyPathService(
    private val competencyRepository: CompetencyRepository,
    private val competencyEdgeRepository: CompetencyEdgeRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val pathProjectionService: PathProjectionService,
    private val competencyGraphVersionService: CompetencyGraphVersionService,
    private val userApi: UserApi,
) {
    @Transactional(readOnly = true)
    fun getPathForMe(authId: String): PathView {
        val userId = resolveUserId(authId)

        val competencies = competencyRepository.findAll()
        val edges = competencyEdgeRepository.findAll()
        val ledger = userCompetencyStateRepository
            .findAllByUserId(userId)
            .associate { it.competencyKey to it.level }

        return pathProjectionService.project(
            competencies = competencies,
            edges = edges,
            targetKeys = competencies.map { it.key }.toSet(),
            ledger = ledger,
            graphVersion = competencyGraphVersionService.currentVersion(),
        )
    }

    private fun resolveUserId(authId: String) =
        userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }
}
