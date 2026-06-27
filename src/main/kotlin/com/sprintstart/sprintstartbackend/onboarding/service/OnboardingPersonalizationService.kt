package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.OnboardingAiClient
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toEntities
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.OnboardingSseEvent
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPathRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class OnboardingPersonalizationService(
    private val onboardingAiClient: OnboardingAiClient,
    private val onboardingPathRepository: OnboardingPathRepository,
    private val userApi: UserApi,
) {
    /**
     * Generates a personalized onboarding path for the user identified by [authId].
     *
     * Reads the user's working area and experience from the user module, deletes any
     * existing path for the user, then opens an SSE stream to the AI service. Each
     * received event is mapped to an [OnboardingSseEvent] and forwarded to the caller.
     * When the AI service emits a `path` event the generated path is persisted before
     * being included in the forwarded event.
     *
     * @param authId External authentication identifier from the JWT subject.
     * @return A cold [Flow] of [OnboardingSseEvent] emitted during path generation.
     */
    @Transactional
    fun personalize(authId: String): Flow<OnboardingSseEvent> {
        val profile = userApi.getOnboardingProfileByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "User with authId: $authId not found") }

        val workingArea = profile.workingArea.toAiScope()
        val experience = profile.experience

        onboardingPathRepository.deleteByUserId(profile.id)

        return onboardingAiClient
            .generatePath(workingArea, experience)
            .map { event ->
                when (event.type) {
                    "stage" -> OnboardingSseEvent(
                        type = "stage",
                        name = event.name,
                        detail = event.detail,
                    )

                    "path" -> {
                        val savedPath = event.path?.let { aiPath ->
                            val entity = aiPath.toEntities(profile.id)
                            onboardingPathRepository.save(entity)
                            entity.toGetForUserResponse()
                        }
                        OnboardingSseEvent(
                            type = "path",
                            path = savedPath,
                        )
                    }

                    "error" -> OnboardingSseEvent(
                        type = "error",
                        message = event.message,
                    )

                    else -> OnboardingSseEvent(type = event.type)
                }
            }
    }
}
