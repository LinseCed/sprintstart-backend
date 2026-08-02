package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.external.enums.Rigor
import com.sprintstart.sprintstartbackend.onboarding.model.entity.ArrivalStep
import com.sprintstart.sprintstartbackend.onboarding.model.response.arrival.ArrivalStepResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.arrival.MyArrivalResponse
import com.sprintstart.sprintstartbackend.onboarding.service.ResolvedArrivalStep

fun ResolvedArrivalStep.toResponse(): ArrivalStepResponse =
    ArrivalStepResponse(
        key = step.key,
        projectId = step.projectId,
        title = step.title,
        description = step.description,
        href = step.href,
        position = step.position,
        settledBy = step.settledBy,
        selfConfirmable = step.selfConfirmable,
        settled = settled,
        settledAt = settledAt,
        rigor = rigor,
    )

/** The authoring view: the definition only, with no hire's state attached to it. */
fun ArrivalStep.toResponse(): ArrivalStepResponse =
    ArrivalStepResponse(
        key = key,
        projectId = projectId,
        title = title,
        description = description,
        href = href,
        position = position,
        settledBy = settledBy,
        selfConfirmable = selfConfirmable,
        settled = false,
        settledAt = null,
        rigor = null,
    )

/**
 * Counts the caller's steps by how each was established.
 *
 * Counting per rigor rather than totalling is the point: an observed step and a self-declared one
 * are different facts, and the single blended number that used to sit here is the defect that made
 * the old onboarding's progress reporting meaningless.
 */
fun List<ResolvedArrivalStep>.toMyArrivalResponse(): MyArrivalResponse =
    MyArrivalResponse(
        steps = map { it.toResponse() },
        observedCount = count { it.rigor == Rigor.OBSERVED },
        declaredCount = count { it.rigor == Rigor.DECLARED },
        outstandingCount = count { !it.settled },
    )
