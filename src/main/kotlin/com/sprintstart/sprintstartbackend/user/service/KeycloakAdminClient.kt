package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.ApplicationConfig
import com.sprintstart.sprintstartbackend.config.KeycloakRoleMapper
import com.sprintstart.sprintstartbackend.user.external.enums.Role
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

interface KeycloakAdminClient {
    fun updateUserProfile(
        authId: String,
        email: String? = null,
        firstName: String? = null,
        lastName: String? = null,
    )

    fun setUserEnabled(authId: String, enabled: Boolean)

    fun setPermissionGroup(authId: String, permissionGroup: Role)

    fun deleteUser(authId: String)
}

@Service
class HttpKeycloakAdminClient(
    private val httpClient: HttpClient,
    private val applicationConfig: ApplicationConfig,
) : KeycloakAdminClient {
    private val objectMapper = jacksonObjectMapper()
    private val adminConfig get() = applicationConfig.keycloak.admin

    override fun updateUserProfile(authId: String, email: String?, firstName: String?, lastName: String?) {
        val payload = mutableMapOf<String, Any>()
        email?.let { payload["email"] = it }
        firstName?.let { payload["firstName"] = it }
        lastName?.let { payload["lastName"] = it }

        if (payload.isNotEmpty()) {
            putUser(authId, payload)
        }
    }

    override fun setUserEnabled(authId: String, enabled: Boolean) {
        putUser(authId, mapOf("enabled" to enabled))
    }

    override fun setPermissionGroup(authId: String, permissionGroup: Role) {
        val token = accessToken()
        val currentRoles = getRealmRoleMappings(authId, token)
        val managedCurrentRoles = currentRoles.filter { it["name"]?.asText() in KeycloakRoleMapper.managedRealmRoles() }

        if (managedCurrentRoles.isNotEmpty()) {
            send(
                method = "DELETE",
                uri = adminUri("/users/$authId/role-mappings/realm"),
                token = token,
                body = objectMapper.writeValueAsString(managedCurrentRoles),
            )
        }

        val targetRole = getRealmRole(KeycloakRoleMapper.toRealmRole(permissionGroup), token)
        send(
            method = "POST",
            uri = adminUri("/users/$authId/role-mappings/realm"),
            token = token,
            body = objectMapper.writeValueAsString(listOf(targetRole)),
        )
    }

    override fun deleteUser(authId: String) {
        send(
            method = "DELETE",
            uri = adminUri("/users/$authId"),
            token = accessToken(),
        )
    }

    private fun putUser(authId: String, payload: Map<String, Any>) {
        send(
            method = "PUT",
            uri = adminUri("/users/$authId"),
            token = accessToken(),
            body = objectMapper.writeValueAsString(payload),
        )
    }

    private fun getRealmRole(roleName: String, token: String): JsonNode {
        val body = send(
            method = "GET",
            uri = adminUri("/roles/${encodePath(roleName)}"),
            token = token,
        )
        return objectMapper.readTree(body)
    }

    private fun getRealmRoleMappings(authId: String, token: String): List<JsonNode> {
        val body = send(
            method = "GET",
            uri = adminUri("/users/$authId/role-mappings/realm"),
            token = token,
        )
        return objectMapper.readTree(body).toList()
    }

    private fun accessToken(): String {
        val form = tokenFormBody()
        val request = HttpRequest
            .newBuilder()
            .uri(tokenRealmUri("/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Keycloak admin token request failed with status ${response.statusCode()}: " +
                    response.body().safeErrorBody(),
            )
        }

        return objectMapper.readTree(response.body())["access_token"]?.asText()
            ?: throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Keycloak admin token response did not contain access_token",
            )
    }

    private fun tokenFormBody(): String {
        val clientSecret = adminConfig.clientSecret
        val username = adminConfig.username
        val password = adminConfig.password

        val pairs = if (!clientSecret.isNullOrBlank()) {
            listOf(
                "grant_type" to "client_credentials",
                "client_id" to adminConfig.clientId,
                "client_secret" to clientSecret,
            )
        } else if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            listOf(
                "grant_type" to "password",
                "client_id" to adminConfig.clientId,
                "username" to username,
                "password" to password,
            )
        } else {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Keycloak admin credentials are not configured")
        }

        return pairs.joinToString("&") { (key, value) -> "${urlEncode(key)}=${urlEncode(value)}" }
    }

    private fun send(method: String, uri: URI, token: String, body: String? = null): String {
        val request = HttpRequest
            .newBuilder()
            .uri(uri)
            .header("Authorization", "Bearer $token")
            .apply {
                if (body != null) {
                    header("Content-Type", "application/json")
                    this.method(method, HttpRequest.BodyPublishers.ofString(body))
                } else {
                    this.method(method, HttpRequest.BodyPublishers.noBody())
                }
            }.build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val status = if (response.statusCode() == 404) HttpStatus.NOT_FOUND else HttpStatus.BAD_GATEWAY
            throw ResponseStatusException(
                status,
                "Keycloak admin request to $uri failed with status ${response.statusCode()}: " +
                    response.body().safeErrorBody(),
            )
        }

        return response.body()
    }

    private fun realmUri(path: String): URI =
        URI.create("${adminConfig.baseUrl.trimEnd('/')}/realms/${encodePath(adminConfig.realm)}$path")

    private fun tokenRealmUri(path: String): URI =
        URI.create("${adminConfig.baseUrl.trimEnd('/')}/realms/${encodePath(adminConfig.tokenRealm)}$path")

    private fun adminUri(path: String): URI =
        URI.create("${adminConfig.baseUrl.trimEnd('/')}/admin/realms/${encodePath(adminConfig.realm)}$path")

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun encodePath(value: String): String =
        urlEncode(value).replace("+", "%20")

    private fun String.safeErrorBody(): String =
        take(MAX_ERROR_BODY_LENGTH).ifBlank { "empty response body" }

    private companion object {
        const val MAX_ERROR_BODY_LENGTH = 500
    }
}
