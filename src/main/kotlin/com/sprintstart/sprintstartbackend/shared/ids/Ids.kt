package com.sprintstart.sprintstartbackend.shared.ids

import java.util.UUID

/** The Keycloak subject of the signed-in user, as the JWT carries it. */
@JvmInline
value class AuthId(
    val value: String,
)

/** A user of SprintStart, as the backend stores them. */
@JvmInline
value class UserId(
    val value: UUID,
)

@JvmInline
value class ProjectId(
    val value: UUID,
)

/** One of a hire's buddy conversations. */
@JvmInline
value class BuddySessionId(
    val value: UUID,
)
