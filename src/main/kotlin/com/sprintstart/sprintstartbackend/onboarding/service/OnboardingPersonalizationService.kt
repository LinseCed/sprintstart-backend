package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.external.model.BlueprintSchema
import com.sprintstart.sprintstartbackend.onboarding.external.model.OnboardingAiPathEvent
import com.sprintstart.sprintstartbackend.onboarding.external.model.SkillAssessmentSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStatus
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toEntities
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toSchema
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.OnboardingSseEvent
import com.sprintstart.sprintstartbackend.onboarding.repository.BlueprintRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPathRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.UserCompetencyStateRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import com.sprintstart.sprintstartbackend.user.external.dto.toAiScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class OnboardingPersonalizationService(
    private val onboardingAiClient: OnboardingAiClient,
    private val onboardingPathRepository: OnboardingPathRepository,
    private val blueprintRepository: BlueprintRepository,
    private val userCompetencyStateRepository: UserCompetencyStateRepository,
    private val competencyRepository: CompetencyRepository,
    private val userApi: UserApi,
    transactionManager: PlatformTransactionManager,
) {
    // Used for the short DB phases only (reading blueprints, deleting the previous path).
    // No AI generation happens here, so no connection is pinned for a slow call.
    private val txTemplate = TransactionTemplate(transactionManager)

    /**
     * Generates a personalized onboarding path for the user identified by [authId].
     *
     * The user's profile (assigned project role) is read up front so a missing user
     * fails fast with 404 before any streaming begins. The returned cold [Flow], once
     * collected, reads the ACTIVE baseline blueprints for the user's scopes
     * (`global` + `area:<role-slug>`). Blueprint authoring is an explicit offline action
     * (propose then PM approve); personalization never generates blueprints on the user's
     * clock. If a required scope has no ACTIVE blueprint, a single `error` event is emitted
     * and nothing else happens — the user's existing path is left intact. Otherwise the
     * previous path (the projection) is deleted and the AI personalization events are
     * streamed; a `path` event is persisted before being forwarded.
     *
     * The path is a disposable projection: this method may delete and rebuild it, but it
     * never touches the durable `UserCompetencyState` ledger.
     *
     * @param authId External authentication identifier from the JWT subject.
     * @return A cold [Flow] of [OnboardingSseEvent] emitted during path generation.
     * @throws ResponseStatusException 404 if no user exists for [authId], 400 if the user
     * does not have exactly one project role assigned.
     */
    fun personalize(authId: String): Flow<OnboardingSseEvent> {
        val profile = userApi
            .getOnboardingProfileByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User with authId: $authId not found") }

        if (profile.projectRoles.size != 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "User must have exactly one project role assigned")
        }

        val scope = profile.projectRoles.single().toAiScope()
        val requiredScopes = listOf("global", "area:$scope")

        return flow {
            // Proficiency comes from the durable competency ledger (chat placement + passed
            // verifications), not the retired self-reported skill wizard -- the ledger is the
            // one skill store the current UI actually writes.
            val skills = withContext(Dispatchers.IO) {
                txTemplate.execute { loadLedgerSkills(profile.id) }
            }.orEmpty()
            val blueprints = withContext(Dispatchers.IO) {
                txTemplate.execute { loadActiveBlueprints(requiredScopes) }
            }.orEmpty()

            val loadedScopes = blueprints.map { it.scope }.toSet()
            val missingScopes = requiredScopes.filterNot { it in loadedScopes }
            if (missingScopes.isNotEmpty()) {
                emit(
                    OnboardingSseEvent(
                        type = "error",
                        message = "Onboarding baseline for ${missingScopes.joinToString(", ")} is not ready yet",
                    ),
                )
                return@flow
            }

            withContext(Dispatchers.IO) {
                txTemplate.executeWithoutResult { onboardingPathRepository.deleteByUserId(profile.id) }
            }

            emitAll(
                onboardingAiClient
                    .generatePath(scope, skills, blueprints)
                    .map { event -> event.toSseEvent(profile.id) },
            )
        }.catch { e ->
            emit(OnboardingSseEvent(type = "error", message = e.message))
        }
    }

    /**
     * Maps an AI [OnboardingAiPathEvent] to the outward [OnboardingSseEvent]. A `path`
     * event's generated path is persisted (in its own repository transaction) before the
     * saved view is forwarded.
     *
     * @param userId [UUID] The id of the user of the onboarding path.
     */
    private fun OnboardingAiPathEvent.toSseEvent(userId: UUID): OnboardingSseEvent =
        when (type) {
            "stage" -> {
                OnboardingSseEvent(type = "stage", name = name, detail = detail)
            }

            "path" -> {
                val savedPath = path?.let { aiPath ->
                    val entity = aiPath.toEntities(userId)
                    onboardingPathRepository.save(entity)
                    entity.toGetForUserResponse()
                }
                OnboardingSseEvent(type = "path", path = savedPath)
            }

            "error" -> {
                OnboardingSseEvent(type = "error", message = message)
            }

            else -> {
                OnboardingSseEvent(type = type)
            }
        }

    /**
     * Loads the ACTIVE blueprints for [scopes] and maps them to the wire schema the AI
     * service consumes. Scopes without an ACTIVE blueprint are skipped. Must be called
     * within a transaction so each blueprint's lazy steps can be read.
     *
     * @param scopes A list of scopes to load active blueprints from.
     */
    private fun loadActiveBlueprints(scopes: List<String>): List<BlueprintSchema> =
        scopes
            .mapNotNull { scope ->
                blueprintRepository.findByScopeAndStatus(scope, BlueprintStatus.ACTIVE)
            }.map { it.toSchema() }

    /**
     * Maps the user's competency ledger to the AI's skill schema: competency label (falling back
     * to the stable key) plus the level name. Level 0 rows (unknown/not yet placed) are skipped --
     * they are not proficiency.
     */
    private fun loadLedgerSkills(userId: UUID): List<SkillAssessmentSchema> {
        val states = userCompetencyStateRepository.findAllByUserId(userId).filter { it.level > 0 }
        if (states.isEmpty()) return emptyList()

        val labelsByKey = competencyRepository
            .findAllByKeyIn(states.map { it.competencyKey })
            .associate { it.key to it.label }

        return states.map { state ->
            SkillAssessmentSchema(
                name = labelsByKey[state.competencyKey] ?: state.competencyKey,
                level = LEVEL_NAMES.getValue(state.level.coerceIn(1, 4)),
            )
        }
    }

    private companion object {
        // Inverse of AssessmentService.LEVEL_RANKS -- ledger 1..4 back to the AI's level names.
        val LEVEL_NAMES = mapOf(
            1 to "beginner",
            2 to "intermediate",
            3 to "advanced",
            4 to "expert",
        )
    }
}
