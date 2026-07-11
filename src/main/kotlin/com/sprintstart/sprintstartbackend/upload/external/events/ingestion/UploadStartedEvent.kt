package com.sprintstart.sprintstartbackend.upload.external.events

import java.util.UUID

data class UploadStartedEvent(
    val transactionId: UUID,
)