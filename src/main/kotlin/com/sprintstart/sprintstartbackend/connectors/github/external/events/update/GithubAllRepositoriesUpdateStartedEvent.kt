package com.sprintstart.sprintstartbackend.connectors.github.external.events.update

import java.util.UUID

/**
 * Emitted when updating all connected GitHub repositories starts.
 *
 * @property transactionId Unique identifier for the update transaction, used for tracking
 * the progress and status of the batch update.
 */
data class GithubAllRepositoriesUpdateStartedEvent(
    val transactionId: UUID,
)
