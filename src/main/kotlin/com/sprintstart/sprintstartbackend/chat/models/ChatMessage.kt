package com.sprintstart.sprintstartbackend.chat.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.*

@Entity
@Table(name = "chat_messages")
internal data class ChatMessage(
    @Column(unique = true, nullable = false) @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val role: ContextRole,
    @Column("chat_id", nullable = false) @ManyToOne val chat: Chat,
    @Column(nullable = false) val content: String,
    @Column("created_at") val createdAt: OffsetDateTime,
)

