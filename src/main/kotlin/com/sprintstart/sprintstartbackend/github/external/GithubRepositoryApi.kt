package com.sprintstart.sprintstartbackend.github.external

import java.util.UUID

interface GithubRepositoryApi {

    fun getRepositoryProjectIdsById(id: UUID) : Set<UUID>
}