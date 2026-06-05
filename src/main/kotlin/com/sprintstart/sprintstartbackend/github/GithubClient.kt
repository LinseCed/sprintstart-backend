package com.sprintstart.sprintstartbackend.github

import com.fasterxml.jackson.databind.JsonNode
import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.github.models.client.PreferredLanguageResponse
import com.sprintstart.sprintstartbackend.shared.web.RequestBuilder
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import org.springframework.stereotype.Component

@Component
class GithubClient(
    private val webClient: WebClient,
    private val applicationConfig: ApplicationConfig,
) {
    suspend fun fetchRepositoryMainLanguage() {
        val body = mapOf(
            "query" to """
        {
          repository(owner: "Tanzkalmar35", name: "JustSync") {
            name
            primaryLanguage {
              name
            }
          }
        }
    """.trimIndent()
        )

        val response = baseQuery()
            .body(body)
            .sync()
            .perform<PreferredLanguageResponse>()

        println(response.data.repository.primaryLanguage.name)
    }

    private fun baseQuery(): RequestBuilder {
        return webClient
            .post()
            .uri(applicationConfig.github.baseUrl)
            .header("Authorization", "Bearer ${applicationConfig.github.token}")
            .header("Content-Type", "application/json")
    }
}