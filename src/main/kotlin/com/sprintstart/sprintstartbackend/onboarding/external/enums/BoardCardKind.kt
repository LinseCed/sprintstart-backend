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
 * Cards the *hire* writes are the exception that proves it: their content is prose, and it is
 * theirs. Nothing reads it back to them as fact, and the mentor cannot touch it.
 *
 * ### Live vs authored
 *
 * A **live** card stores nothing but its own existence: the content is re-read on every board load
 * from the same services the buddy's tools use, so a card and the tool of the same name can never
 * disagree. An **authored** card is frozen at the moment it was written and stores its content in
 * the row, because there is nowhere else for it to live.
 */
enum class BoardCardKind(
    val placement: Placement,
) {
    /**
     * The moments between joining and a first accepted piece of work, and which have happened.
     *
     * Universal across tracks: the underlying timeline is composed from contributions, not from
     * pull requests, so it says something true for a Scrum Master as well as a developer. The words
     * come from the hire's track, which is why the board carries its vocabulary.
     */
    PATH_TO_FIRST_CONTRIBUTION(Placement.BASELINE),

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
    OPEN_PULL_REQUESTS(Placement.BASELINE),

    /**
     * The task the hire is on, and where it came from.
     *
     * Not part of the baseline, because it is only true some of the time — somebody with no claimed
     * goal and no Task 0 is not "between tasks", they simply have no task, and a card about nothing
     * is worse than no card. The mentor places it, and confirming `claim_goal` places it too.
     */
    CURRENT_TASK(Placement.MENTOR),

    /**
     * Good next tasks for the hire, ranked, each with the plain reason it was suggested.
     *
     * The other half of the pair: worth pinning when somebody is looking for work, pointless when
     * they already have some. Which of those is true is exactly the kind of thing the mentor knows
     * from the conversation and the board does not.
     */
    SUGGESTED_TASKS(Placement.MENTOR),

    /** Something the hire wrote down. Markdown, theirs, and nothing reads it back as fact. */
    NOTE(Placement.AUTHORED),

    /** A link the hire wants to keep. The smallest possible card, and probably the most used. */
    LINK(Placement.AUTHORED),

    /** A list the hire ticks off. The only card whose content changes by being *used*. */
    CHECKLIST(Placement.AUTHORED),
    ;

    /**
     * How a card of this kind gets onto a board.
     *
     * The distinction is between *what a hire always needs*, *what the mentor decided was worth
     * keeping*, and *what the hire put there themselves* — and it decides everything downstream:
     * which kinds are ensured automatically, which the buddy may place, which may appear more than
     * once, and who may edit one.
     */
    enum class Placement {
        /**
         * Ensured on every board read, without anybody deciding.
         *
         * Deterministic on purpose: nobody should depend on a language model noticing that their
         * pull request has been waiting a week.
         */
        BASELINE,

        /**
         * Placed by the mentor, in conversation.
         *
         * The mentor chooses *that* the card belongs there; its content is still a live read, so it
         * never chooses what the card says.
         */
        MENTOR,

        /**
         * Written by the hire.
         *
         * The only cards that carry stored content, the only ones a board may hold several of, and
         * the only ones the mentor must never touch. A board the mentor can tidy is a board the
         * hire cannot trust to keep what they put on it.
         */
        AUTHORED,
    }
}
