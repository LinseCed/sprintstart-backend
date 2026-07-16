package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One turn of a [SkillAssessmentSession]: the interviewer's [question], and the candidate's
 * [answer] once they've replied (`null` while the turn is still open).
 *
 * Ordered by [turnIndex], the session's turns flatten directly into the AI service's stateless
 * `history` (question -> an `assistant` entry, answer -> a `user` entry) so the interviewer can
 * re-derive its belief on every call without the backend replaying anything but this table.
 */
@Entity
@Table(name = "skill_assessment_turns")
class SkillAssessmentTurn(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne
    @JoinColumn(name = "session_id", nullable = false)
    val session: SkillAssessmentSession,
    @Column(name = "turn_index", nullable = false)
    val turnIndex: Int,
    @Column(nullable = false, columnDefinition = "TEXT")
    val question: String,
    @Column(nullable = true, columnDefinition = "TEXT")
    var answer: String? = null,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
)
