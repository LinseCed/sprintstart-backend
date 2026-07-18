package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.external.model.ProposedStarterTaskSchema
import com.sprintstart.sprintstartbackend.onboarding.model.entity.StarterWorkTaskProposal
import com.sprintstart.sprintstartbackend.onboarding.model.response.starterwork.StarterWorkTaskProposalResponse

fun StarterWorkTaskProposal.toResponse(): StarterWorkTaskProposalResponse =
    StarterWorkTaskProposalResponse(
        id = id,
        sourceId = sourceId,
        title = title,
        summary = summary,
        rationale = rationale,
        sourceUrl = sourceUrl,
        competencyKeys = competencyKeys.toList(),
        status = status,
    )

/** Maps a live proposal back into the wire shape the AI service's `/match` endpoint expects. */
fun StarterWorkTaskProposal.toSchema(): ProposedStarterTaskSchema =
    ProposedStarterTaskSchema(
        sourceId = sourceId,
        title = title,
        summary = summary ?: "",
        competencyKeys = competencyKeys.toList(),
        rationale = rationale ?: "",
    )
