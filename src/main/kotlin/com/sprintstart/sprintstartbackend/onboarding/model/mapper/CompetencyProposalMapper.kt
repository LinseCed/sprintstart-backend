package com.sprintstart.sprintstartbackend.onboarding.model.mapper

import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdgeProposal
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyProposal
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyEdgeProposalResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyProposalResponse

fun CompetencyProposal.toResponse(): CompetencyProposalResponse =
    CompetencyProposalResponse(
        id = id,
        key = key,
        label = label,
        description = description,
        kind = kind,
        repoRef = repoRef,
        status = status,
    )

fun CompetencyEdgeProposal.toResponse(): CompetencyEdgeProposalResponse =
    CompetencyEdgeProposalResponse(
        id = id,
        fromKey = fromKey,
        toKey = toKey,
        kind = kind,
        rationale = rationale,
        status = status,
    )

/** Maps an approved proposal into the real [Competency] it becomes. */
fun CompetencyProposal.toCompetency(): Competency =
    Competency(
        key = key,
        label = label,
        description = description,
        kind = kind,
        repoRef = repoRef,
    )

/** Maps an approved proposal into the real [CompetencyEdge] it becomes. */
fun CompetencyEdgeProposal.toCompetencyEdge(): CompetencyEdge =
    CompetencyEdge(
        fromKey = fromKey,
        toKey = toKey,
        kind = kind,
    )
