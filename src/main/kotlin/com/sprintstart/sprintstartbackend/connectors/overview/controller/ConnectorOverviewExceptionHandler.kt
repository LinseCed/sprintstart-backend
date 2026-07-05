package com.sprintstart.sprintstartbackend.connectors.overview.controller

import com.sprintstart.sprintstartbackend.connectors.overview.models.exceptions.ConnectorConfigurationNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

/**
 * Handles exceptions thrown during the operation of the application and maps them
 * to appropriate HTTP responses.
 *
 * This class is annotated with `@ControllerAdvice` to centralize exception handling
 * across the application. Each exception handler method catches specific exceptions
 * and returns a meaningful error response with an appropriate HTTP status code.
 */
@ControllerAdvice
class ConnectorOverviewExceptionHandler {
    /**
     * Handles exceptions of type [ConnectorConfigurationNotFoundException] and converts them into
     * a standardized error response with a 404 NOT FOUND HTTP status code.
     *
     * @param ex The exception containing details about the repository that could not be found.
     * @return A [ResponseEntity] containing the [ErrorResponse] with the exception's message
     *         and an HTTP status of 404 (NOT FOUND).
     */
    @ExceptionHandler(ConnectorConfigurationNotFoundException::class)
    fun handleRepositoryNotFound(ex: ConnectorConfigurationNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(ex.message))
}

data class ErrorResponse(
    val message: String?,
)
