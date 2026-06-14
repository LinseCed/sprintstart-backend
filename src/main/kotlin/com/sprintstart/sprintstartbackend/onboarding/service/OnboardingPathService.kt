package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.OnboardingPath
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toCreateResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetAllResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.mapper.toGetResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.CreateOnboardingPathResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathForUserResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.path.GetOnboardingPathsResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.OnboardingPathRepository
import com.sprintstart.sprintstartbackend.user.external.UserApi
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class OnboardingPathService(
    private val onboardingPathRepository: OnboardingPathRepository,
    private val userApi: UserApi,
) {
    //  ========================== Methods for users ==========================

    /**
     * Creates a new onboarding path for the given user.
     *
     * @param userId The ID of the user to create the path for.
     * @return The created path response.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no user exists with [userId].
     */
    fun createOnboardingPathForUser(userId: UUID): CreateOnboardingPathResponse {
        if (!userApi.exists(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with id: $userId")
        }
        return onboardingPathRepository.save(OnboardingPath(userId = userId)).toCreateResponse()
    }

    /**
     * Retrieves all onboarding paths across all users.
     *
     * @return A list of all onboarding paths.
     */
    fun getAllOnboardingPathOverviews(): List<GetOnboardingPathsResponse> {
        return onboardingPathRepository.findAll().map {
            it.toGetAllResponse()
        }
    }

    fun getOnboardingPathOverviewByUserId(userId: UUID): GetOnboardingPathResponse {
        if (!userApi.exists(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with id: $userId")
        }

        return onboardingPathRepository
            .findOnboardingPathByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No onboarding path found with for: $userId") }
            .toGetResponse()
    }

    /**
     * Retrieves the onboarding path associated with a specific user.
     *
     * @param userId The ID of the user.
     * @return The user's onboarding path response.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no user exists with [userId],
     *   or if no path has been created for that user yet.
     */
    fun getOnboardingPathByUserId(userId: UUID): GetOnboardingPathForUserResponse {
        if (!userApi.exists(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with id: $userId")
        }
        return onboardingPathRepository
            .findOnboardingPathByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No path found for user with id: $userId") }
            .toGetForUserResponse()
    }

    @Transactional(readOnly = true)
    fun getOnboardingPathByAuthId(authId: String): GetOnboardingPathForUserResponse {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }

        return onboardingPathRepository
            .findOnboardingPathByUserId(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No path found for user with id: $userId") }
            .toGetForUserResponse()
    }

    fun deleteOnboardingPathByAuthId(authId: String) {
        val userId = userApi
            .getUserIdByAuthId(authId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: $authId") }

        onboardingPathRepository.deleteByUserId(userId)
    }

    /**
     * Deletes an onboarding path by its ID.
     *
     * No-op if no path exists with the given ID.
     *
     * @param pathId The ID of the onboarding path to delete.
     */
    fun deleteOnboardingPathById(pathId: UUID) {
        onboardingPathRepository.deleteById(pathId)
    }

    /**
     * Deletes the onboarding path associated with a specific user.
     *
     * @param userId The ID of the user whose path should be deleted.
     * @throws ResponseStatusException with [HttpStatus.NOT_FOUND] if no user exists with [userId].
     */
    fun deleteOnboardingPathByUserId(userId: UUID) {
        if (!userApi.exists(userId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with id: $userId")
        }
        onboardingPathRepository.deleteByUserId(userId)
    }
}

// TODO: add doc
