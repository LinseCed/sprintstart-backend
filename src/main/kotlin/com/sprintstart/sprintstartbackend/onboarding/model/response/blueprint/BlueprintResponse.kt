package com.sprintstart.sprintstartbackend.onboarding.model.response.blueprint

data class BlueprintResponse(
    val scope: String,
    val version: String,
    val competencies: List<BlueprintCompetencyResponse>,
)
