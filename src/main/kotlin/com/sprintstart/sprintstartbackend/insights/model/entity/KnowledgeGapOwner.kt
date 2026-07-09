package com.sprintstart.sprintstartbackend.insights.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import java.util.UUID

/**
 * A person responsible for a [KnowledgeGap]'s component.
 *
 * [externalUserId] is the user identifier reported by the AI service; it is exposed to API clients
 * as the owner id, while [id] remains the internal primary key of this cache row.
 */
@Entity
class KnowledgeGapOwner(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "external_user_id", nullable = false)
    val externalUserId: String,
    @Column(nullable = false)
    val username: String,
    @Column(nullable = false)
    val firstname: String,
    @Column(nullable = false)
    val lastname: String,
    @Column(nullable = false)
    val workingArea: String,
    @ManyToOne(optional = false)
    @JoinColumn(name = "knowledge_gap_id", nullable = false)
    val knowledgeGap: KnowledgeGap,
)
