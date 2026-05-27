package com.sprintstart.sprintstartbackend.chat.models

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "chats")
internal data class Chat(
    @Column(unique = true, nullable = false) @Id val id: UUID = UUID.randomUUID(),
    @Column val title: String = "",
    @Column("user_id", nullable = false) val userId: UUID,
    @Column("created_at") val createdAt: OffsetDateTime,
) {
    init {
        require(userId != UUID(0, 0)) { "userId must not be empty" }
    }
}

