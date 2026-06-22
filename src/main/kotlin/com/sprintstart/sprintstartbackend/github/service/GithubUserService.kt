package com.sprintstart.sprintstartbackend.github.service

import com.sprintstart.sprintstartbackend.github.models.GithubUser
import com.sprintstart.sprintstartbackend.github.models.GithubUserPat
import com.sprintstart.sprintstartbackend.github.models.api.requests.AddPatRequest
import com.sprintstart.sprintstartbackend.github.models.api.requests.GetPatRequest
import com.sprintstart.sprintstartbackend.github.models.api.requests.RemovePatRequest
import com.sprintstart.sprintstartbackend.github.models.api.requests.UpdatePatNameRequest
import com.sprintstart.sprintstartbackend.github.models.api.requests.UpdatePatRequest
import com.sprintstart.sprintstartbackend.github.models.exceptions.GithubUserPatNotFoundException
import com.sprintstart.sprintstartbackend.github.repository.GithubUserRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Timed
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GithubUserService(
    private val githubUserRepository: GithubUserRepository,
) {
    /**
     * Retrieves the personal access token (PAT) associated with the given GitHub user credentials.
     *
     * @param authId The authentication ID of the user whose PAT is being requested.
     * @param request The request object containing the name of the GitHub entity for which the PAT is required.
     * @return The personal access token (PAT) if found.
     * @throws GithubUserPatNotFoundException If no PAT is found for the given user and name.
     */
    @Timed("Retrieving GitHub PAT")
    @Transactional(readOnly = true)
    fun getPat(authId: String, request: GetPatRequest): String {
        return githubUserRepository.findByAuthIdAndName(authId, request.name)?.token
            ?: throw GithubUserPatNotFoundException(request.name, authId)
    }

    /**
     * Adds a new personal access token (PAT) for a GitHub user.
     *
     * @param authId The identifier of the authenticated user.
     * @param request The request object containing the details of the personal access token to be added.
     */
    @Timed("Adding new GitHub PAT")
    fun addPat(authId: String, request: AddPatRequest) {
        val userPat = GithubUserPat(authId, request.name)
        val entity = GithubUser(userPat, request.token)
        githubUserRepository.save(entity)
    }

    /**
     * Updates the PAT (Personal Access Token) of a GitHub user identified by the provided name
     * within the repository. If the user is not found, an exception is thrown.
     *
     * @param authId The authentication ID of the requesting user.
     * @param request The request containing the name of the GitHub user and the new PAT to be updated.
     */
    @Timed("Updating GitHub PAT")
    fun updatePat(authId: String, request: UpdatePatRequest) {
        val userPatEntity =
            githubUserRepository.findByAuthIdAndName(authId, request.name) ?: throw GithubUserPatNotFoundException(
                request.name,
                authId,
            )
        userPatEntity.token = request.newToken
        githubUserRepository.save(userPatEntity)
    }

    /**
     * Updates the PAT (Personal Access Token) name for a user identified by the authentication ID.
     *
     * @param authId The authentication ID of the user whose PAT name is to be updated.
     * @param request The request object containing the old name and the new name for the PAT.
     * @throws GithubUserPatNotFoundException If no PAT is found with the specified name for the given user.
     */
    @Timed("Updating GitHub PAT name")
    fun updatePatName(authId: String, request: UpdatePatNameRequest) {
        val userPatEntity =
            githubUserRepository.findByAuthIdAndName(authId, request.oldName) ?: throw GithubUserPatNotFoundException(
                request.oldName,
                authId,
            )
        userPatEntity.id.name = request.newName
        githubUserRepository.save(userPatEntity)
    }

    /**
     * Removes a personal access token (PAT) associated with a GitHub user.
     *
     * @param authId The authorization ID of the user performing the operation.
     * @param request The request object containing the details of the PAT to be removed, including the name of the PAT.
     * @throws GithubUserPatNotFoundException If no PAT is found with the specified name for the given user.
     */
    @Timed("Deleting GitHub PAT")
    fun removePat(authId: String, request: RemovePatRequest) {
        val userPat =
            githubUserRepository.findByAuthIdAndName(authId, request.name) ?: throw GithubUserPatNotFoundException(
                request.name,
                authId,
            )
        githubUserRepository.delete(userPat)
    }
}
