package com.sprintstart.sprintstartbackend.connectors.jira

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.GetIssuesOfProjectRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.GetProjectsOfInstanceResponse
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentials
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraAuthException
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraResourceNotFoundException
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import com.sprintstart.sprintstartbackend.shared.web.WebClientException
import org.springframework.stereotype.Component
import java.net.URLEncoder

@Component
class JiraClient(
    private val webClient: WebClient,
) {
    companion object {
        private const val DEFAULT_MAX_RESULTS = 100
        private val DEFAULT_FIELDS = listOf(
            "summary",
            "status",
            "description",
            "issuetype",
            "priority",
            "assignee",
            "reporter",
            "project",
            "created",
            "updated",
            "comment",
        )
        private val DEFAULT_EXPAND = listOf("changelog")
    }

    // ── Issues (with inline comments & changelog) ───────────────────────────

    suspend fun searchIssues(
        baseUrl: String,
        credentials: JiraCredentials,
        jql: String,
        fields: List<String> = DEFAULT_FIELDS,
        expand: List<String> = DEFAULT_EXPAND,
        extraFields: List<String> = emptyList(),
    ): List<JiraIssue> {
        val requestFields = (fields + extraFields).joinToString(",")
        val requestExpand = expand.joinToString(",")

        return paginateSearch(baseUrl, jql, requestFields, requestExpand, credentials)
    }

    // ── Issue comments (escape hatch when inline payload is too large) ───────

    suspend fun getIssueComments(
        baseUrl: String,
        credentials: JiraCredentials,
        issueKey: String,
    ): List<JiraComment> {
        val endpoint = "/rest/api/3/issue/${issueKey.encodePath()}/comment"
        return paginateValues(baseUrl, endpoint, credentials)
    }

    // ── Issue changelog (escape hatch when not using expand=changelog) ───────

    suspend fun getIssueChangelog(
        baseUrl: String,
        credentials: JiraCredentials,
        issueKey: String,
    ): List<JiraChangelogHistory> {
        val endpoint = "/rest/api/3/issue/${issueKey.encodePath()}/changelog"
        return paginateValues(baseUrl, endpoint, credentials)
            .let { changelog: JiraChangelog -> changelog.histories }
    }

    // ── Projects ────────────────────────────────────────────────────────────

    suspend fun getProjects(
        baseUrl: String,
        credentials: JiraCredentials,
    ): List<JiraProject> {
        // /rest/api/3/project is not paginated in the same way; returns an JSON array.
        return webClient
            .get()
            .uri("$baseUrl/rest/api/3/project")
            .header("Authorization", credentials.authorizationHeader())
            .sync()
            .perform()
    }

    // ── Fields (needed to resolve custom field IDs) ─────────────────────────

    suspend fun getFields(
        baseUrl: String,
        credentials: JiraCredentials,
    ): List<JiraField> {
        return webClient
            .get()
            .uri("$baseUrl/rest/api/3/field")
            .header("Authorization", credentials.authorizationHeader())
            .sync()
            .perform()
    }

    // ── Sprints (Agile API) ────────────────────────────────────────────────

    suspend fun getSprints(
        baseUrl: String,
        credentials: JiraCredentials,
        boardId: Long,
    ): List<JiraSprint> {
        val endpoint = "/rest/agile/1.0/board/$boardId/sprint"
        return paginateValues(baseUrl, endpoint, credentials)
    }

    // ── Pagination ──────────────────────────────────────────────────────────

    private suspend inline fun <reified T> paginateSearch(
        baseUrl: String,
        jql: String,
        fields: String,
        expand: String,
        credentials: JiraCredentials,
    ): List<T> {
        val results = mutableListOf<T>()
        var startAt = 0

        while (true) {
            val query = mapOf(
                "jql" to jql,
                "startAt" to startAt.toString(),
                "maxResults" to DEFAULT_MAX_RESULTS.toString(),
                "fields" to fields,
                "expand" to expand,
            )
            val uri = buildUri(baseUrl, "/rest/api/3/search", query)

            val page: SearchResponse<T> = performGet(uri, credentials)

            results.addAll(page.issues)

            if (page.isLast || startAt + page.maxResults >= page.total) {
                break
            }
            startAt += page.maxResults
        }

        return results
    }

    private suspend inline fun <reified T> paginateValues(
        baseUrl: String,
        endpoint: String,
        credentials: JiraCredentials,
        query: Map<String, String> = emptyMap(),
    ): List<T> {
        val results = mutableListOf<T>()
        var startAt = 0

        while (true) {
            val paginatedQuery = query + mapOf(
                "startAt" to startAt.toString(),
                "maxResults" to DEFAULT_MAX_RESULTS.toString(),
            )
            val uri = buildUri(baseUrl, endpoint, paginatedQuery)

            val page: ValuesResponse<T> = performGet(uri, credentials)

            results.addAll(page.values)

            if (page.isLast || startAt + page.maxResults >= page.total) {
                break
            }
            startAt += page.maxResults
        }

        return results
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private suspend inline fun <reified T> performGet(
        uri: String,
        credentials: JiraCredentials,
    ): T {
        return try {
            webClient
                .get()
                .uri(uri)
                .header("Authorization", credentials.authorizationHeader())
                .header("Accept", "application/json")
                .sync()
                .perform()
        } catch (e: WebClientException) {
            when (e.statusCode) {
                401, 403 -> throw JiraAuthException(e.body)
                404 -> throw JiraResourceNotFoundException("Jira resource not found at $uri: ${e.body}")
                else -> throw e
            }
        }
    }

    private fun buildUri(baseUrl: String, path: String, query: Map<String, String>): String {
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
        return "$baseUrl$path?$queryString"
    }

    private fun JiraCredentials.authorizationHeader(): String = "${this.userEmail}:${this.apiKey}"

    private fun String.encodePath(): String {
        return URLEncoder.encode(this, Charsets.UTF_8)
    }
}
