package com.sprintstart.sprintstartbackend

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Contains the following application.yml config parameters
 *
 * ```yml
 * sprintstart:
 *     ai: ...
 *     github: ...
 * ```
 */
@ConfigurationProperties(prefix = "sprintstart")
data class ApplicationConfig(
    val ai: AiConfig,
    val github: GithubConfig,
    val keycloak: KeycloakConfig = KeycloakConfig(),
)

/**
 * Contains the following application.yml config parameters
 *
 * ```yml
 * sprintstart:
 *     ai:
 *         base-url: ...
 * ````
 */
data class AiConfig(
    @get:JsonProperty("base-url")
    val baseUrl: String,
)

/**
 * Contains the following application.yml config parameters
 *
 * ´´´yml
 * sprintstart:
 *     github:
 *         base-url: ...
 *         token: ...
 *         cron: ...
 * ´´´
 */
data class GithubConfig(
    @get:JsonProperty("base-url")
    val baseUrl: String,
    @get:JsonProperty("repo-base-url")
    val repoBaseUrl: String,
    @get:JsonProperty("token")
    val token: String,
    @get:JsonProperty("cron")
    val cron: String,
)

data class KeycloakConfig(
    val admin: KeycloakAdminConfig = KeycloakAdminConfig(),
)

data class KeycloakAdminConfig(
    @get:JsonProperty("base-url")
    val baseUrl: String = "http://localhost:8081/auth",
    val realm: String = "sprintstart",
    @get:JsonProperty("token-realm")
    val tokenRealm: String = "master",
    @get:JsonProperty("client-id")
    val clientId: String = "admin-cli",
    @get:JsonProperty("client-secret")
    val clientSecret: String? = null,
    val username: String? = null,
    val password: String? = null,
)
