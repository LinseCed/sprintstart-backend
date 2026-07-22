package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * A hire's ongoing onboarding buddy companion -- one continuous conversation per user, unlike the
 * general-purpose `chat` module's multiple user-created chats. The AI buddy endpoint is stateless;
 * this session plus its [BuddyMessage]s is what makes the conversation durable across visits,
 * mirroring how [SkillAssessmentSession] durably backs the stateless interviewer.
 */
@Entity
@Table(
    name = "buddy_sessions",
    uniqueConstraints = [UniqueConstraint(name = "uq_buddy_sessions_user", columnNames = ["user_id"])],
)
class BuddySession(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    /**
     * The AI-written running summary of this conversation's oldest [summarizedCount] messages.
     *
     * Now that the buddy is the hire's front door, conversations only get longer — re-sending the
     * whole transcript every turn is unbounded. Only the window after [summarizedCount] is sent,
     * with this summary standing in for the rest. It is a prompt-shaping device, never the
     * record: the full transcript stays in `buddy_messages`.
     */
    @Column(nullable = true, columnDefinition = "TEXT")
    var summary: String? = null,
    /** How many of the oldest persisted messages [summary] covers. */
    @Column(name = "summarized_count", nullable = false)
    var summarizedCount: Int = 0,
)
