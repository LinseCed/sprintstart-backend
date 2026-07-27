package com.sprintstart.sprintstartbackend.onboarding.model.entity

import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardOwner
import com.sprintstart.sprintstartbackend.onboarding.external.enums.BoardCardState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * One card on a [Board].
 *
 * ### What a row does and does not hold
 *
 * For a live card the row holds no content at all — only that this hire wants this card, where it
 * sits, and whether it is still there. The content is re-read on every board load from the same
 * services the buddy's tools read, which is what makes a card and the tool of the same name unable
 * to disagree. Storing a copy would be a second record of facts that already live somewhere
 * durable, and this codebase has repeatedly found that the copy is the one that goes stale.
 *
 * Authored cards — a note, a drawn diagram — genuinely have content of their own and will bring a
 * payload column with them in the slice that adds them. A nullable column nothing writes yet would
 * just be dead wiring.
 *
 * ### One row per kind, for now
 *
 * Every kind in this slice is a single live read, so a second copy would be the same card twice —
 * hence the unique constraint, which also makes "ensure this card exists" idempotent. Authored
 * cards break that (several notes are several notes), and the slice that adds them is the slice
 * that relaxes it.
 */
@Entity
@Table(
    name = "board_cards",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_board_cards_board_kind", columnNames = ["board_id", "kind"]),
    ],
)
class BoardCard(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "board_id", nullable = false)
    val boardId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: BoardCardKind,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val owner: BoardCardOwner,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var state: BoardCardState = BoardCardState.ACTIVE,
    /**
     * Where the card sits in the board's order, ascending.
     *
     * An integer rather than an x/y pair: the board is a responsive grid a hire can reorder, not a
     * canvas they position things on. A free canvas was considered and deferred — it would not
     * survive a phone screen, and reordering is the part that carries the meaning.
     */
    @Column(nullable = false)
    var position: Int,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
