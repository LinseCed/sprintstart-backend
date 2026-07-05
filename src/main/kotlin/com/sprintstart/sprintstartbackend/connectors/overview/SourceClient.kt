package com.sprintstart.sprintstartbackend.connectors.overview

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import org.springframework.stereotype.Component
import java.net.URI

@Component
class SourceClient(
    val applicationConfig: ApplicationConfig,
    val webClient: WebClient,
) {
    /**
     * Patches a list of sources by changing their statuses in the AI system.
     *
     * Patching a source means changing it's behaviour for the AI. In order to do that, the AI system keeps track
     * of enabled and disabled sources internally. Because of that, we only need to tell them what we want.
     *
     * @param sources description
     */
    suspend fun patchSources(sources: Map<String, Boolean>) {
        webClient
            .patch()
            .uri(aiUri("/api/v1/sources"))
            .body(sources)
            .sync()
            .perform<Unit>()
    }

    /**
    * Prefixes a given path with the AI base url from the application config.
    *
    * @param path The path to be prefixed.
    * @return the full path (prefix + given path), concatenated.
    */
    private fun aiUri(path: String): URI = URI.create("${applicationConfig.ai.baseUrl}$path")
}
