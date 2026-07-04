package com.sprintstart.sprintstartbackend.connectors.overview

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.connectors.overview.models.ConnectorSource
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import java.net.URI

class SourceClient(
    val applicationConfig: ApplicationConfig,
    val webClient: WebClient,
) {
    suspend fun patchSources(sources: Map<String, Boolean>) {
        webClient
            .patch()
            .uri(aiUri("/api/v1/sources"))
            .body(sources)
            .sync()
            .perform<Unit>()
    }

    private fun aiUri(path: String): URI = URI.create("${applicationConfig.ai.baseUrl}$path")
}
