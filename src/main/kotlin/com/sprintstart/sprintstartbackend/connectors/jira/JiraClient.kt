package com.sprintstart.sprintstartbackend.connectors.jira

import com.sprintstart.sprintstartbackend.connectors.jira.model.api.request.GetIssuesOfProjectRequest
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.GetProjectsOfInstanceResponse
import com.sprintstart.sprintstartbackend.connectors.jira.model.api.response.JiraIssueResponse
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraCredentials
import com.sprintstart.sprintstartbackend.connectors.jira.model.entity.JiraIssue
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraAuthException
import com.sprintstart.sprintstartbackend.connectors.jira.model.exceptions.JiraResourceNotFoundException
import com.sprintstart.sprintstartbackend.shared.web.WebClient
import com.sprintstart.sprintstartbackend.shared.web.WebClientException
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Component
import java.net.URLEncoder

@Component
class JiraClient(
    private val webClient: WebClient,
) {
    private val defaultMaxResults = 100
    private val defaultFields = listOf(
        "issuetype",
        "project",
        "parent",
        "resolution",
        "resolutiondate",
        "created",
        "priority",
        "labels",
        "versions",
        "assignee",
        "updated",
        "status",
        "components",
        "issuekey",
        "description",
        "timetracking",
        "summary",
        "creator",
        "subtasks",
        "reporter",
        "duedate",
        "comment",
        "environment",
        "issuelinks"
    )
    private val defaultExpand = listOf("changelog")

    suspend fun searchIssues(
        baseUrl: String,
        credentials: JiraCredentials,
        jql: String,
        fields: List<String> = defaultFields,
        expand: List<String> = defaultExpand,
        extraFields: List<String> = emptyList(),
    ): List<JiraIssueResponse> {
        val requestFields = (fields + extraFields).joinToString(",")
        val requestExpand = expand.joinToString(",")

        return doFetchAll(baseUrl, jql, requestFields, requestExpand, credentials)
    }

    private suspend inline fun <reified T> doFetchAll(
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
                "maxResults" to defaultMaxResults.toString(),
                "fields" to fields,
                "expand" to expand,
            )
            val uri = buildUri(baseUrl, "/rest/api/3/search", query)

            val page: PageableResponse<T> = performGet(uri, credentials)

            results.addAll(page.getValues())

            if (page.isLast()) {
                break
            }
            startAt += defaultMaxResults
        }

        return results
    }

    /**
     * Performs a GET request to a given uri synchronously and returns the returned values deserialized into [T].
     *
     * @param uri The uri to request.
     * @param credentials The credentials used for authorization.
     * @return The resulting json deserialized into [T].
     * @throws [JiraAuthException] (401/403) if the request was denied by the origin.
     * @throws [JiraResourceNotFoundException] (404) if the requested endpoint does not exist.
     */
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
                .perform<T>()
        } catch (e: WebClientException) {
            when (e.statusCode) {
                401, 403 -> throw JiraAuthException(e.body)
                404 -> throw JiraResourceNotFoundException("Jira resource not found at $uri: ${e.body}")
                else -> throw e
            }
        }
    }

    /**
     * Builds a valid Jira Cloud Rest Api request uri out of the base Jira Cloud instance url, the endpoint path, and a
     * number of request filters.
     *
     * @param baseUrl The base uri to the Jira Cloud instance (`https://my-app.atlassian.net ...`)
     * @param path The endpoint path (`... /rest/api/3/search/jql ...`)
     * @param query The query to append to the path (`... ?key1=value1&key2=value2& ...`)
     * @return The formatted request uri (`baseUrl/path/query`)
     */
    private fun buildUri(baseUrl: String, path: String, query: Map<String, String>): String {
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${key.encodePath()}=${value.encodePath()}"
        }
        return "$baseUrl$path?$queryString"
    }

    /**
     * Extends [JiraCredentials] by formatting the credential values as a valid credential authorization header in Jira
     * Cloud requests.
     *
     * @return The formatted credential values.
     */
    private fun JiraCredentials.authorizationHeader(): String = "${this.userEmail}:${this.apiKey}"

    /**
     * Encodes a given string into a valid path string.
     *
     * That means replacing all characters that are invalid in paths (for example ' ') with their UTF-8 representations.
     * In the case of ' ', that means '%20'.
     *
     * @return The encoded string, ready for use in a path.
     */
    private fun String.encodePath(): String {
        return URLEncoder.encode(this, Charsets.UTF_8)
    }
}

interface PageableResponse<T> {
    fun getValues(): List<T>
    fun isLast(): Boolean
}

@Serializable
data class PaginatedIssuesSearchResponse(
    val issues: List<JiraIssueResponse>,
    val isLast: Boolean = false,
    val nextPageToken: String? = null,
) : PageableResponse<JiraIssueResponse> {
    override fun getValues(): List<JiraIssueResponse> {
        return this.issues
    }

    override fun isLast(): Boolean {
        return this.isLast
    }
}
