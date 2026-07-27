package com.sprintstart.sprintstartbackend.onboarding.controller

import com.sprintstart.sprintstartbackend.onboarding.model.response.board.BoardResponse
import com.sprintstart.sprintstartbackend.onboarding.service.BoardService
import com.sprintstart.sprintstartbackend.user.external.UserApi
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The hire's own board.
 *
 * Self-serve only, and the caller is always resolved from the token rather than taken as a
 * parameter: there is no "read somebody else's board" here to get the authorisation wrong on. A PM
 * view of a hire's board was considered and deferred — the board is the hire's working surface, and
 * the PM's read of the same facts already exists on the metrics readout.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(
    name = "Onboarding - Board",
    description = "Your persistent board: the cards the mentor curates and you own",
)
class BoardController(
    private val boardService: BoardService,
    private val userApi: UserApi,
) {
    @Operation(
        summary = "My board on a project",
        description = "Every active card on your board for this project, in board order, each " +
            "with its content read live — so a card and the buddy tool behind it can never say " +
            "different things. The board is created on first read, holding the cards relevant to " +
            "your track.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Board returned"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "404", description = "You are not a member of that project"),
        ],
    )
    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/me/board")
    @PreAuthorize("hasAnyRole('USER', 'PM', 'HR', 'ADMIN')")
    fun getMyBoard(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam projectId: UUID,
    ): BoardResponse =
        boardService.getBoard(resolveUserId(jwt), projectId)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "You are not a member of that project",
            )

    @Operation(
        summary = "Take a card off my board",
        description = "Removes a card from your board for good. The buddy will not put it back — " +
            "a dismissal is a decision, not a gesture the next page load undoes. Dismissing a " +
            "card that is already gone is not an error.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "Card dismissed"),
            ApiResponse(responseCode = "401", description = "Authentication required"),
            ApiResponse(responseCode = "404", description = "No such card on a board of yours"),
        ],
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/me/board/cards/{cardId}")
    @PreAuthorize("hasAnyRole('USER', 'PM', 'HR', 'ADMIN')")
    fun dismissCard(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable cardId: UUID,
    ) {
        // A card on somebody else's board and a card that does not exist answer the same, on
        // purpose: a 403 here would confirm that a given id is a real card of somebody's.
        if (!boardService.dismiss(resolveUserId(jwt), cardId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such card on your board")
        }
    }

    private fun resolveUserId(jwt: Jwt): UUID =
        userApi.getUserIdByAuthId(jwt.subject).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "No user found with authId: ${jwt.subject}")
        }
}
