package com.sprintstart.sprintstartbackend.user.external.enums

/**
 * Whether the GitHub account somebody declared actually exists.
 *
 * A login is typed in by hand, and the value is what artifact verification compares a pull
 * request's author against — so a typo does not fail loudly, it silently stops crediting work the
 * hire really did, while leaving them looking calm rather than blocked. This is what lets the app
 * say *"we could not find that account"* before that happens.
 *
 * ### There is no "does not exist" by inference
 *
 * Only a definitive answer from GitHub is recorded. A rate limit, a network failure or an outage
 * leaves the field **null**, which reads as *not checked* — never as [NOT_FOUND]. That is the same
 * rule the orientation cache holds to: an outage is not evidence about the world, and a wrong
 * "that account does not exist" in front of somebody whose account is fine is worse than saying
 * nothing.
 *
 * Null therefore covers three real cases that need no distinguishing, because the action for all
 * three is the same — try again later: never checked, checked and could not tell, and no login
 * declared at all.
 */
enum class GithubLoginVerification {
    /** GitHub confirmed an account with this login exists. */
    VERIFIED,

    /** GitHub answered definitively that no account has this login. Almost always a typo. */
    NOT_FOUND,
}
