package com.sprintstart.sprintstartbackend.connectors.jira.repository

import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentials
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
internal interface JiraCredentialsRepository : JpaRepository<JiraCredentials, UUID> {
    @Query(
        """
        select c from jira_credentials c where c.user_email = :email;
    """,
        nativeQuery = true,
    )
    fun findByUserEmail(email: String): JiraCredentials?
}
