package com.sprintstart.sprintstartbackend.onboarding.model.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The last picture assembled for one [BoardCard] of kind `DIAGRAM` — a cache, and nothing more.
 *
 * ### Why a live card has a stored row at all
 *
 * Because a diagram costs an LLM call to derive and a board card hydrates on *every* page load.
 * Without this, opening the board would mean waiting on a generation, every time. So the picture is
 * cached and the cache is **validated, never trusted**: every revalidation sends
 * [corpusFingerprint], an unchanged corpus comes back `unchanged` with no retrieval and no
 * generation, and a corpus that has moved is redrawn. The same rule `TaskOrientationService` keeps,
 * for the same reason — a diagram of code that has since changed is worse than no diagram, because
 * the reader cannot tell.
 *
 * The *question* is not here: it lives on [BoardCard.subject], because that is the card's identity
 * and it survives the cache being dropped.
 *
 * ### Why the picture is JSON and a note is not
 *
 * The same argument [BoardCardPayload] makes — small, read and written whole, never queried into —
 * with one consequence that runs the opposite way. A note that will not decode fails the board read
 * on purpose: it is the hire's own work, and silently blanking it would look like the board lost
 * something. A diagram that will not decode is simply a **cache miss**, redrawn on the next
 * revalidation, because everything in it is derivable and nothing in it was anybody's work.
 */
@Entity
@Table(name = "board_diagrams")
class BoardDiagram(
    /** The card this is the picture for. One card, one cached diagram. */
    @Id
    @Column(name = "card_id")
    val cardId: UUID,
    /**
     * The corpus this picture was drawn from.
     *
     * The whole cache-validation mechanism: sent on every revalidation so the AI service can answer
     * "nothing changed" without doing any work, and compared against the *current* corpus rather
     * than against a clock. Age is not staleness — a diagram of code nobody has touched in a year is
     * perfectly current.
     */
    @Column(name = "corpus_fingerprint")
    var corpusFingerprint: String? = null,
    @Column(name = "model")
    var model: String? = null,
    /** The assembled nodes, edges and sources as JSON. See the class note for why it is not rows. */
    @Column(columnDefinition = "TEXT", nullable = false)
    var payload: String,
    @Column(name = "assembled_at", nullable = false)
    var assembledAt: Instant = Instant.now(),
)
