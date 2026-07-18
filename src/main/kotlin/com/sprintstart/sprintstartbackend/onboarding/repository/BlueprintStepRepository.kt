package com.sprintstart.sprintstartbackend.onboarding.repository

import com.sprintstart.sprintstartbackend.onboarding.model.entity.BlueprintStep
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BlueprintStepRepository : JpaRepository<BlueprintStep, UUID>
