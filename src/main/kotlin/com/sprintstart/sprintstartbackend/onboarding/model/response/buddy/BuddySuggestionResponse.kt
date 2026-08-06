package com.sprintstart.sprintstartbackend.onboarding.model.response.buddy

/**
 * One thing this hire could usefully ask their buddy, offered as a chip beside the composer.
 *
 * Exists because the buddy's most useful capabilities were reachable only by knowing what to type.
 * A hire who does not know an action exists — or does not know the chat is how you reach it — never
 * triggers it, which is the tutor's *"dann wird es kaum verwendet werden"*.
 *
 * [question] is put in the composer and **never sent**: the hire presses send. The chip removes the
 * guesswork about vocabulary, not the hire's authorship of the question. [label] is what the chip
 * says, kept short enough to read at a glance in a 384 px panel.
 */
data class BuddySuggestionResponse(
    val label: String,
    val question: String,
)
