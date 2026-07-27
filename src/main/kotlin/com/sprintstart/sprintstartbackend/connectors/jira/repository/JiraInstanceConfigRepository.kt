package com.sprintstart.sprintstartbackend.connectors.jira.repository

import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraInstanceConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
internal interface JiraInstanceConfigRepository : JpaRepository<JiraInstanceConfig, String> {
    @Query(
        """
        SELECT * FROM jira_instance_configs c 
        WHERE c.next_sync_at <= :due
    """,
        nativeQuery = true,
    )
    fun findAllDue(due: Instant): List<JiraInstanceConfig>
}
