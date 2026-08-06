package com.sprintstart.sprintstartbackend.user.service

import com.sprintstart.sprintstartbackend.user.model.entity.User
import com.sprintstart.sprintstartbackend.user.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Owns writing a user's Jira display name: normalization and uniqueness.
 *
 * The sibling of [GithubLoginService], and it exists for the same reason rather than for symmetry:
 * this value is what attributes an ingested Jira issue to a hire, so getting it wrong is a
 * measurement bug, not a cosmetic profile flaw. One writer, however many entry points.
 *
 * ### Why the rules differ from a GitHub login
 *
 * **It is not lower-cased.** A GitHub login is case-insensitive at the source, so folding case
 * there loses nothing. A Jira display name is a *person's name*, rendered back to them and matched
 * against what Jira renders — folding it would both misspell somebody and stop matching.
 *
 * **There is no syntax rule.** Anything Jira renders is a valid display name; inventing a pattern
 * would reject real people for looking wrong to a regex.
 *
 * ⚠️ **The one thing this cannot defend against is a namesake inside Jira.** Uniqueness here means
 * no two *SprintStart users* claim one name. If two Jira accounts genuinely share a display name,
 * nothing ingested distinguishes them — the connector drops Jira's `accountId` at parse time — and
 * their work would land on one record. Parsing that id is the fix when somebody hits it.
 */
@Service
class JiraDisplayNameService(
    private val userRepository: UserRepository,
) {
    /**
     * Stores [jiraDisplayName] on [user].
     *
     * A blank value clears it, so somebody can withdraw a wrong name rather than being stuck with
     * it — and clearing is also how a hire opts out of having their Jira work counted at all.
     *
     * @throws ResponseStatusException 409 when another user already claims it, which would credit
     * one person's issues to the other.
     */
    fun apply(user: User, jiraDisplayName: String) {
        // Collapsed rather than merely trimmed: a name pasted out of Jira's UI arrives with the
        // odd double space, and "Ada  Lovelace" must not be a different person from "Ada Lovelace".
        val normalized = jiraDisplayName.trim().replace(WHITESPACE_RUN, " ")

        if (normalized.isEmpty()) {
            user.jiraDisplayName = null
            return
        }

        if (userRepository.existsByJiraDisplayNameAndIdNot(normalized, user.id)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Jira user '$normalized' is already linked to another user",
            )
        }

        user.jiraDisplayName = normalized
    }

    private companion object {
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
