package com.sprintstart.sprintstartbackend.chat.models.requests

import jakarta.validation.constraints.Min

internal data class GetChatsRequest(
    @Min(1) val limit: Int?,
)

