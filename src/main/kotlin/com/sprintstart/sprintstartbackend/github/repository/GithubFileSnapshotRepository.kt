package com.sprintstart.sprintstartbackend.github.repository

import com.sprintstart.sprintstartbackend.github.models.GithubFileSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GithubFileSnapshotRepository : JpaRepository<GithubFileSnapshot, UUID> {
}