package com.sprintstart.sprintstartbackend.connectors.jira.repository

import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentials
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentialsId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
internal interface JiraCredentialsRepository : JpaRepository<JiraCredentials, JiraCredentialsId> {
    @Query(
        """
        select c from jira_credentials c where c.user_email = :userEmail
    """,
        nativeQuery = true,
    )
    fun findAllByUserEmail(userEmail: String): List<JiraCredentials>
}
