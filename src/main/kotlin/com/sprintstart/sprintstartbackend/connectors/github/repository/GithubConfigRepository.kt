package com.sprintstart.sprintstartbackend.connectors.github.repository

import com.sprintstart.sprintstartbackend.connectors.github.models.GithubConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface GithubConfigRepository : JpaRepository<GithubConfig, UUID> {
    @Query("""
        SELECT * FROM gh_configs c 
        WHERE c.next_sync_at <= :due
    """, nativeQuery = true)
	fun findAllDue(due: Instant): List<GithubConfig>
}
