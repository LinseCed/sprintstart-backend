package com.sprintstart.sprintstartbackend.user.model.request.user

import java.util.UUID

data class PatchMeRequest(
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val profileIcon: String? = null,
    // The GitHub account the user contributes as, used to attribute a submitted pull request to
    // them during artifact verification. Blank clears it.
    val githubLogin: String? = null,
    val projectsId: Set<UUID>,
)
