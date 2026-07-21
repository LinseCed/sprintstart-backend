package com.sprintstart.sprintstartbackend.connectors.github.controller

import com.sprintstart.sprintstartbackend.connectors.github.models.exceptions.SourceNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GithubExceptionHandlerTest {
    private val handler = GithubExceptionHandler()

    @Test
    fun `handleSourceNotFound returns 404 with the exception message`() {
        val exception = SourceNotFoundException("abc-123")

        val response = handler.handleSourceNotFound(exception)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body?.message).isEqualTo("Source with id abc-123 not found.")
    }
}
