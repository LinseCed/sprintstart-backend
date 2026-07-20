package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintCompetency
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BlueprintCompetencyRepository : JpaRepository<BlueprintCompetency, UUID>
