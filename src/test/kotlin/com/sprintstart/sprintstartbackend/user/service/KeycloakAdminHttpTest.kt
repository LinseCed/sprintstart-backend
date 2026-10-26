package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.shared.config.ApplicationConfig
import com.sprintstart.sprintstartbackend.shared.config.KeycloakAdminConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class KeycloakAdminHttpTest {
    private val httpClient = mockk<HttpClient>()
    private val transport = KeycloakAdminTransport(httpClient)
    private val uri = URI.create("http://keycloak/admin/realms/sprintstart/users/1")

    private fun respond(status: Int, body: String) {
        val response = mockk<HttpResponse<String>> {
            every { statusCode() } returns status
            every { body() } returns body
        }
        every { httpClient.send(any(), any<HttpResponse.BodyHandler<String>>()) } returns response
    }

    private fun header(request: HttpRequest, name: String): String? = request.headers().firstValue(name).orElse(null)

    @Test
    fun `a successful request returns the body and carries the bearer token`() {
        val sent = slot<HttpRequest>()
        val response = mockk<HttpResponse<String>> {
            every { statusCode() } returns 204
            every { body() } returns "done"
        }
        every { httpClient.send(capture(sent), any<HttpResponse.BodyHandler<String>>()) } returns response

        val body = transport.send("PUT", uri, "tok", """{"a":1}""")

        assertThat(body).isEqualTo("done")
        assertThat(sent.captured.method()).isEqualTo("PUT")
        assertThat(header(sent.captured, "Authorization")).isEqualTo("Bearer tok")
        assertThat(header(sent.captured, "Content-Type")).isEqualTo("application/json")
    }

    @Test
    fun `a request without a body sends no content type`() {
        val sent = slot<HttpRequest>()
        val response = mockk<HttpResponse<String>> {
            every { statusCode() } returns 200
            every { body() } returns "[]"
        }
        every { httpClient.send(capture(sent), any<HttpResponse.BodyHandler<String>>()) } returns response

        transport.send("GET", uri, "tok")

        assertThat(header(sent.captured, "Content-Type")).isNull()
    }

    @Test
    fun `a missing resource is a 404 and carries what Keycloak said`() {
        respond(404, "no such user")

        val error = assertThrows<ResponseStatusException> { transport.send("GET", uri, "tok") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(error.reason).contains("404").contains("no such user").contains(uri.toString())
    }

    @Test
    fun `any other failure is a 502, with a placeholder for an empty body`() {
        respond(500, "  ")

        val error = assertThrows<ResponseStatusException> { transport.send("GET", uri, "tok") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        assertThat(error.reason).contains("500").contains("empty response body")
    }

    @Test
    fun `a long error body is cut off`() {
        respond(502, "x".repeat(2_000))

        val error = assertThrows<ResponseStatusException> { transport.send("GET", uri, "tok") }

        assertThat(error.reason!!.substringAfter(": ")).hasSize(500)
    }

    @Test
    fun `the redirect boundary statuses are failures and the success boundaries are not`() {
        respond(200, "ok")
        assertThat(transport.send("GET", uri, "t")).isEqualTo("ok")
        respond(299, "ok")
        assertThat(transport.send("GET", uri, "t")).isEqualTo("ok")
        respond(300, "x")
        assertThrows<ResponseStatusException> { transport.send("GET", uri, "t") }
        respond(199, "x")
        assertThrows<ResponseStatusException> { transport.send("GET", uri, "t") }
    }

    private fun uris(baseUrl: String?, jwkSetUri: String = "", issuerUri: String = ""): KeycloakAdminUris {
        val config = mockk<ApplicationConfig>()
        every { config.keycloak.admin } returns KeycloakAdminConfig(baseUrl = baseUrl)
        return KeycloakAdminUris(config, jwkSetUri, issuerUri)
    }

    @Test
    fun `a configured base url builds the admin and token urls, without a trailing slash`() {
        val uris = uris("http://kc:8080/")

        assertThat(uris.adminUri("/users")).isEqualTo(URI.create("http://kc:8080/admin/realms/sprintstart/users"))
        assertThat(uris.tokenRealmUri("/x")).isEqualTo(URI.create("http://kc:8080/realms/master/x"))
    }

    @Test
    fun `without a base url it is derived from the jwk set uri, then the issuer uri`() {
        assertThat(uris(" ", jwkSetUri = "http://a/realms/r/protocol/certs").adminUri("/u").toString())
            .startsWith("http://a/admin/realms/")
        assertThat(uris(null, issuerUri = "http://b/realms/r/").adminUri("/u").toString())
            .startsWith("http://b/admin/realms/")
    }

    @Test
    fun `with nothing to derive it from the base url is a 502`() {
        val error = assertThrows<ResponseStatusException> { uris(null, "http://no-realm-here", "").adminUri("/u") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
    }

    @Test
    fun `path segments are percent encoded with spaces as percent 20`() {
        val uris = uris("http://kc")

        assertThat(uris.encodePath("a b/c")).isEqualTo("a%20b%2Fc")
        assertThat(uris.urlEncode("a b")).isEqualTo("a+b")
    }
}
