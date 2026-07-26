package com.sprintstart.sprintstartbackend.connectors.jira.service

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials.AddCredentialRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials.ChangeJiraCredentialNameRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials.ChangeJiraCredentialTokenRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.credentials.DeleteJiraCredentialRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.credentials.JiraCredentialsDto
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.credentials.toDto
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredential
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentialsId
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraCredentialAlreadyExistsException
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraCredentialNotFoundException
import com.sprintstart.sprintstartbackend.connectors.jira.repository.JiraCredentialsRepository
import com.sprintstart.sprintstartbackend.shared.annotations.Tracked
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class JiraCredentialsService(
    private val credentialsRepository: JiraCredentialsRepository,
) {
    @Tracked("Storing a new Jira credential")
    fun addCredentials(request: AddCredentialRequest) {
        if (credentialsRepository.existsById(JiraCredentialsId(request.userEmail, request.tokenName))) {
            throw JiraCredentialAlreadyExistsException(request.userEmail, request.tokenName)
        }

        val credentials = JiraCredential(JiraCredentialsId(request.userEmail, request.tokenName), request.authToken)
        credentialsRepository.save(credentials)
    }

    @Transactional(readOnly = true)
    @Tracked("Retrieving Jira credentials of a user")
    fun getCredentialsOfUser(userEmail: String): List<JiraCredentialsDto> =
        credentialsRepository.findAllByUserEmail(userEmail).map { it.toDto() }

    @Tracked("Removing Jira credentials")
    fun removeCredentials(request: DeleteJiraCredentialRequest) {
        if (!credentialsRepository.existsById(JiraCredentialsId(request.userEmail, request.tokenName))) {
            throw JiraCredentialNotFoundException(request.userEmail, request.tokenName)
        }

        credentialsRepository.deleteById(JiraCredentialsId(request.userEmail, request.tokenName))
    }

    @Tracked("Changing Jira credential name")
    fun changeCredentialName(request: ChangeJiraCredentialNameRequest): JiraCredentialsDto {
        val credential = credentialsRepository
            .findById(JiraCredentialsId(request.userEmail, request.oldName))
            .orElseThrow {
                throw JiraCredentialNotFoundException(request.userEmail, request.oldName)
            }

        credential.id.name = request.newName
        credentialsRepository.save(credential)
        return credential.toDto()
    }

    @Tracked("Changing Jira credential name")
    fun changeCredentialToken(request: ChangeJiraCredentialTokenRequest): JiraCredentialsDto {
        val credential = credentialsRepository
            .findById(JiraCredentialsId(request.userEmail, request.tokenName))
            .orElseThrow {
                throw JiraCredentialNotFoundException(request.userEmail, request.tokenName)
            }

        credential.authToken = request.newToken
        credentialsRepository.save(credential)
        return credential.toDto()
    }
}
