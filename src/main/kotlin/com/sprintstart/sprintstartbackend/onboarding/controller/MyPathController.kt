package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.response.path.PathView
import com.sprintstart.sprintstartbackend.onboarding.service.CompetencyPathService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * A hire's path.
 *
 * There is nothing to create, generate or delete here any more: the path is **derived** on every
 * read from the competency graph, the project's baseline, and the hire's ledger. It used to be a
 * persisted per-user tree that had to be generated and could be thrown away and rebuilt; that tree
 * is gone (backend#53), and with it the whole generate/regenerate dance.
 *
 * An empty path is a real answer, not a 404: it means the project's baseline selects nothing
 * visible yet.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "Onboarding - My path", description = "The competency path derived for the current user")
class MyPathController(
    private val competencyPathService: CompetencyPathService,
) {
    /**
     * Returns the authenticated user's competency path for one project.
     *
     * Nodes are `mastered`/`available`/`locked`, with the prerequisite edges between them and the
     * module (if any) that teaches each one. Reading this never writes anything a hire has earned.
     *
     * @param jwt Authenticated JWT used to resolve the current user.
     * @param projectId The project to project the path for. Onboarding is per-project.
     */
    @Operation(
        summary = "Get the current user's competency path",
        description = "Projects the path from the competency graph, the project's baseline and " +
            "the user's ledger. Derived on read: nothing is generated or stored.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Path returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "404", description = "No user found for the token"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/path")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN', 'PM', 'HR')")
    fun getPathForMe(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam projectId: UUID,
    ): PathView = competencyPathService.getPathForMe(jwt.subject, projectId)
}
