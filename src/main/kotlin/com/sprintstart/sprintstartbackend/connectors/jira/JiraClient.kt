package com.sprintstart.sprintstartbackend.connectors.jira

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.GetIssuesOfProjectRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.GetProjectsOfInstanceResponse
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import org.springframework.stereotype.Component

@Component
class JiraClient(
    private val webClient: WebClient,
) {
    /**
     * Fetches the list of projects from the specified Jira instance.
     *
     * @param uri The base URI of the Jira instance from which to retrieve the projects.
     * @return A response object containing the list of projects available in the Jira instance.
     */
    suspend fun getProjects(uri: String): GetProjectsOfInstanceResponse {
        val baseProjectsEndpoint = "$uri/rest/api/3/project"

        return webClient
            .get()
            .uri(baseProjectsEndpoint)
            .sync()
            .perform<GetProjectsOfInstanceResponse>()
    }

    suspend fun getIssues(uri: String, project: String) {
        val baseJqlEndpoint = "$uri/rest/api/3/search/jql"
        val jql = "project = $project"

        webClient
            .post()
            .uri(baseJqlEndpoint)
            .body(GetIssuesOfProjectRequest(jql, 100))
            .sync()
            .perform<Unit>()
    }
}
