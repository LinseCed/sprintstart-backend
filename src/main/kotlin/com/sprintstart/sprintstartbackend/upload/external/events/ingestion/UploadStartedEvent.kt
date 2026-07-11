package com.sprintstart.sprintstartbackend.upload.external.events.ingestion

import java.util.UUID

data class UploadStartedEvent(
    val transactionId: UUID,
)
