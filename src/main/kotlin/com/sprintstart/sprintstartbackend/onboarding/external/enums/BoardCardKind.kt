package com.sprintstart.sprintstartbackend.onboarding.external.enums

/**
 * The bounded catalog of cards a board can hold.
 *
 * ### Why a catalog and not free-form content
 *
 * The buddy curates this board, and the buddy is a language model. A card kind it does not
 * recognise is a card it could invent the contents of — so the set is closed, and both the buddy
 * and the UI understand every member of it. A card the buddy places is therefore a request to show
 * a *known* read, never a request to render prose it wrote about the hire's state. That is the same
 * rule every other buddy surface holds to: the mentor decides what to show, the backend decides
 * what it says.
 *
 * ### Live vs authored
 *
 * A **live** card stores nothing but its own existence: the content is re-read on every board load
 * from the same services the buddy's tools use, so a card and the tool of the same name can never
 * disagree. An **authored** card (a note, a diagram) is frozen at placement and does have stored
 * content — those arrive in a later slice, which is also when this table stops being one row per
 * kind.
 */
enum class BoardCardKind {
    /**
     * The moments between joining and a first accepted piece of work, and which have happened.
     *
     * Universal across tracks: the underlying timeline is composed from contributions, not from
     * pull requests, so it says something true for a Scrum Master as well as a developer. The words
     * come from the hire's track, which is why the board carries its vocabulary.
     */
    PATH_TO_FIRST_CONTRIBUTION,

    /**
     * The hire's still-open pull requests, named, with how long each has waited.
     *
     * Genuinely pull-request-shaped rather than generically about work in flight: it lists numbers,
     * titles and links, and a [Contribution] deliberately carries none of those — it is a
     * measurement surface, not a record of artifacts. So this card is mounted exactly where the
     * buddy's `get_my_open_pull_requests` tool is mounted, on a track that admits pull requests,
     * and is simply absent otherwise. An empty "your open pull requests" card in front of somebody
     * who will never have one is the invisible-hire problem in card form.
     */
    OPEN_PULL_REQUESTS,
}
