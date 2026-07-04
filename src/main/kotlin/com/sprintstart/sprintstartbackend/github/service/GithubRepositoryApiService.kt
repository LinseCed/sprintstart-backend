package com.sprintstart.sprintstartbackend.github.service

import com.sprintstart.sprintstartbackend.github.external.GithubRepositoryApi
import com.sprintstart.sprintstartbackend.github.repository.GithubRepositoryConnectionRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GithubRepositoryApiService(private val githubRepositoryConnectionRepository: GithubRepositoryConnectionRepository) : GithubRepositoryApi {
    override fun getRepositoryProjectIdsById(id: UUID) : Set<UUID> {
        val repo = githubRepositoryConnectionRepository.findById(id).orElseThrow {
            NoSuchElementException("Repository with id $id not found")
        }
        return repo.projectIds
    }
}