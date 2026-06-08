package com.sprintstart.sprintstartbackend.github.repository

import com.sprintstart.sprintstartbackend.github.models.GithubRepositorySnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GithubRepositorySnapshotRepository : JpaRepository<GithubRepositorySnapshot, UUID> {
}