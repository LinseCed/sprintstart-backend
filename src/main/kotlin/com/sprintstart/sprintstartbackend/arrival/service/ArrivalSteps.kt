package com.sprintstart.sprintstartbackend.arrival.service

import com.sprintstart.sprintstartbackend.arrival.external.enums.ArrivalDerivation
import com.sprintstart.sprintstartbackend.arrival.external.enums.Rigor
import com.sprintstart.sprintstartbackend.arrival.model.mapper.toResponse
import com.sprintstart.sprintstartbackend.arrival.model.response.arrival.ArrivalStepResponse
import com.sprintstart.sprintstartbackend.shared.ids.ProjectId
import com.sprintstart.sprintstartbackend.shared.ids.UserId
import org.springframework.stereotype.Component

/**
 * Arrival steps for the assistants and the board, in the shape the arrival pages serve.
 *
 * [ArrivalStepService] owns the rules; this only answers in [ArrivalStepResponse], so nothing outside
 * arrival holds a step it could change behind the service's back. A null project is the company-wide list.
 */
@Component
class ArrivalSteps(
    private val arrivalStepService: ArrivalStepService,
) {
    /** [userId]'s steps across every project they are on, as [ArrivalStepService.forHire]. */
    fun forHire(userId: UserId): List<ArrivalStepResponse> =
        arrivalStepService.forHire(userId.value).map { it.toResponse() }

    /** [userId]'s steps on [projectId] alone, as [ArrivalStepService.forHireOn]. */
    fun forHireOn(userId: UserId, projectId: ProjectId): List<ArrivalStepResponse> =
        arrivalStepService.forHireOn(userId.value, projectId.value).map { it.toResponse() }

    /** The steps authored on [projectId], or the company-wide ones for null. */
    fun authoredOn(projectId: ProjectId?): List<ArrivalStepResponse> =
        arrivalStepService.listForAuthoring(projectId?.value).map { it.toResponse() }

    /** The step [key] names on [projectId], or null. */
    fun stepOn(key: String, projectId: ProjectId): ArrivalStepResponse? =
        authoredOn(projectId).firstOrNull { it.key == key }

    /** Which derivations the system can settle on [projectId], as [ArrivalStepService.derivable]. */
    fun derivable(projectId: ProjectId): List<Pair<ArrivalDerivation, Boolean>> =
        arrivalStepService.derivable(projectId.value)

    /** Creates [steps] on [projectId], as [ArrivalStepService.createAll]. */
    fun createAll(projectId: ProjectId, steps: List<NewArrivalStep>): List<ArrivalStepResponse> =
        arrivalStepService.createAll(projectId.value, steps).map { it.toResponse() }

    /** Applies [change] to the step [key] on [projectId], as [ArrivalStepService.update]. */
    fun update(key: String, projectId: ProjectId, change: ArrivalStepChange): ArrivalStepResponse =
        arrivalStepService
            .update(
                key = key,
                projectId = projectId.value,
                title = change.title,
                description = change.description,
                href = change.href,
                position = change.position,
                settledBy = change.settledBy,
            ).toResponse()

    /** Puts the steps on [projectId] in [orderedKeys]' order, as [ArrivalStepService.reorder]. */
    fun reorder(projectId: ProjectId, orderedKeys: List<String>): List<ArrivalStepResponse> =
        arrivalStepService.reorder(projectId.value, orderedKeys).map { it.toResponse() }

    /** Deletes the step [key] on [projectId], as [ArrivalStepService.delete]. */
    fun delete(key: String, projectId: ProjectId) = arrivalStepService.delete(key, projectId.value)
}

/** The fields of an arrival step to change; null leaves a field as it is. */
data class ArrivalStepChange(
    val title: String? = null,
    val description: String? = null,
    val href: String? = null,
    val position: Int? = null,
    val settledBy: Rigor? = null,
)
