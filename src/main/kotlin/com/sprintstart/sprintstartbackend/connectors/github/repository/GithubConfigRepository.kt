package com.sprintstart.sprintstartbackend.connectors.github.repository

import com.sprintstart.sprintstartbackend.connectors.github.models.GithubConfig
import org.springframework.data.jpa.repository.JpaRepository

interface GithubConfigRepository : JpaRepository<GithubConfig, UUID> {
	
}
