package com.sprintstart.sprintstartbackend.connectors.github.service

import com.sprintstart.sprintstartbackend.connectors.github.models.ConnectionState
import com.sprintstart.sprintstartbackend.connectors.github.models.GithubRepositoryConnection

/**
 * Maps a GitHub repository connection to the stable source-status vocabulary shared by the
 * connector overview and the ingestion status APIs.
 *
 * A disabled source always reports `DISABLED`, regardless of its underlying connection state, so a
 * paused repository is not shown as actively connected.
 */
internal fun GithubRepositoryConnection.toSourceStatus(): String {
    if (!sourceEnabled) {
        return "DISABLED"
    }

    return when (connectionState) {
        ConnectionState.UP_TO_DATE -> "CONNECTED"
        ConnectionState.UPDATING -> "UPDATING"
        ConnectionState.OUT_OF_DATE -> "OUT_OF_DATE"
        ConnectionState.FAILED -> "FAILED"
    }
}
