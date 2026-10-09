package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.external.enums.BuddyMessageRole
import com.sprintstart.sprintstartbackend.onboarding.model.entity.BuddyTeamMessage

/**
 * How many of the latest replies keep an area open: enough for "make it shorter", "ok, send it" after a
 * draft, few enough that a conversation changing topic does not mount every area at once.
 */
internal const val AREA_OPEN_FOR_REPLIES = 3

/**
 * The team areas whose tools are mounted while one manager message is answered.
 *
 * An area is here because one of the latest replies opened or used it ([carriedOver]), or because it is
 * opened during this turn ([open]). The carry-over is what makes "yes, send it" work a message after a
 * draft: the transcript is text only, so nothing else remembers the area was open.
 *
 * Each reply stores what it opened or used ([activeThisTurn]). An area in use stays open; one left alone
 * closes after [AREA_OPEN_FOR_REPLIES] replies.
 */
internal class OpenAreas(
    carriedOver: Set<TeamArea> = emptySet(),
) {
    val mounted: MutableSet<TeamArea> = carriedOver.toMutableSet()

    val activeThisTurn: MutableSet<TeamArea> = mutableSetOf()

    fun open(area: TeamArea) {
        mounted.add(area)
        activeThisTurn.add(area)
    }

    fun use(area: TeamArea) {
        if (area in mounted) {
            activeThisTurn.add(area)
        }
    }
}

/**
 * The areas the latest [AREA_OPEN_FOR_REPLIES] replies of the current visit opened or used.
 *
 * A visit begins at the last greeting, so a new visit starts with nothing mounted.
 */
internal fun List<BuddyTeamMessage>.areasStillOpen(): Set<TeamArea> =
    drop(indexOfLast { it.opening }.coerceAtLeast(0))
        .filter { it.role == BuddyMessageRole.ASSISTANT && !it.opening }
        .takeLast(AREA_OPEN_FOR_REPLIES)
        .flatMap { it.openedAreas.toTeamAreas() }
        .toSet()

/** The stored form of the areas a reply opened or used: sorted, comma-separated names; null when none. */
internal fun Set<TeamArea>.encoded(): String? =
    takeIf { it.isNotEmpty() }?.sortedBy { it.name }?.joinToString(",") { it.name }

/** The areas a stored [encoded] value names; a name that is no longer an area is dropped. */
internal fun String?.toTeamAreas(): Set<TeamArea> =
    this
        ?.split(',')
        ?.mapNotNull { name -> TeamArea.entries.firstOrNull { it.name == name.trim() } }
        ?.toSet()
        .orEmpty()
