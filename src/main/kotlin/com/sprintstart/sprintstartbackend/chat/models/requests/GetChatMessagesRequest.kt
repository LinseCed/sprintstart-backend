package com.sprintstart.sprintstartbackend.chat.models.requests

import jakarta.validation.constraints.Min

data class GetChatMessagesRequest(
    @Min(1) val limit: Int?,
)

