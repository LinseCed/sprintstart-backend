package com.sprintstart.sprintstartbackend.onboarding.model.entity

/**
 * How binding one [BlueprintCompetency] entry is within its baseline.
 *
 * [REQUIRED] entries are the mandatory part of "shared, mandatory, PM-owned": everyone in the
 * scope must reach them. [RECOMMENDED] entries still land on the path but carry no mandate.
 */
enum class BlueprintRequirement {
    REQUIRED,
    RECOMMENDED,
    ;

    /** The lowercase form used on the AI wire and in the existing API contract. */
    fun wireValue(): String = name.lowercase()

    companion object {
        /** Parses the wire form, defaulting to [RECOMMENDED] for anything unrecognized. */
        fun fromWire(value: String?): BlueprintRequirement =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: RECOMMENDED
    }
}
