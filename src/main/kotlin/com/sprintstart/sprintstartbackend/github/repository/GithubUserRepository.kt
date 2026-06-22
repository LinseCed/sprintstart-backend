package com.sprintstart.sprintstartbackend.github.repository

import com.sprintstart.sprintstartbackend.github.models.GithubUser
import com.sprintstart.sprintstartbackend.github.models.GithubUserPat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface GithubUserRepository : JpaRepository<GithubUser, GithubUserPat> {
    @Query(
        """
        SELECT u FROM gh_user u
        WHERE u.auth_id = :authId AND u.name = :name
    """,
        nativeQuery = true,
    )
    fun findByAuthIdAndName(authId: String, name: String): GithubUser?
}
