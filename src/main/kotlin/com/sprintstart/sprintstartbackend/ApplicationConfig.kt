package com.sprintstart.sprintstartbackend

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service

@ConfigurationProperties(prefix = "sprintstart")
internal data class ApplicationConfig(
    val ai: AiConfig
)

internal data class AiConfig(
    @get:JsonProperty("base-url")
    val baseUrl: String
)
