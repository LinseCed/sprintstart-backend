package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * A hire's persistent working surface on one project.
 *
 * ### Why this exists
 *
 * The buddy conversation opens fresh every visit: the previous window is folded into the mentor's
 * private memory and never replayed. That was a deliberate choice, and it leaves a hole — anything
 * durable the mentor shows you (where you stand, which pull request is stuck, what to do next)
 * scrolls away and is gone by the next visit. The board is where those things live instead.
 *
 * Chat is the conversation. The board is the whiteboard beside it: the shared surface the mentor
 * curates and the hire owns.
 *
 * ### Per project, lazily created
 *
 * Keyed by `(userId, projectId)` like the rest of onboarding, because what belongs on it — the
 * ramp, the open work, the suggested tasks — is per project. Created on first read rather than
 * when somebody joins, so nobody accumulates empty boards for projects they never onboard on.
 */
@Entity
@Table(
    name = "boards",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_boards_user_project", columnNames = ["user_id", "project_id"]),
    ],
)
class Board(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "project_id", nullable = false)
    val projectId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
