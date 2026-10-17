package com.sprintstart.sprintstartbackend.shared.ids

import java.util.UUID

/*
 * Identifiers with their own type (AI_RULES R7): a user id cannot be passed where a session id belongs,
 * which the compiler now checks. Controllers wrap the raw value once, at the edge; entities and DTOs keep
 * their UUIDs and are converted where they are read.
 */

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

/** A project. */
@JvmInline
value class ProjectId(
    val value: UUID,
)

/** One of a hire's buddy conversations. */
@JvmInline
value class BuddySessionId(
    val value: UUID,
)
