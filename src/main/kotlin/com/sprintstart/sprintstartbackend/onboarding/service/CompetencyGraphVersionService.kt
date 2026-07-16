package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyGraphVersion
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyGraphVersionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Tracks the competency graph's current version as a single row.
 *
 * There is no way to edit the graph outside the dev seeder yet, so this is deliberately minimal:
 * a version number bumped only when something actually changes, with no history or change
 * classification (that needs graph-authoring and a real "session start" hook to exist first).
 */
@Service
class CompetencyGraphVersionService(
    private val repository: CompetencyGraphVersionRepository,
) {
    /** The graph's current version; `1` if it has never been bumped. */
    @Transactional(readOnly = true)
    fun currentVersion(): Int = repository.findTopByOrderByVersionDesc()?.version ?: 1

    /**
     * Records that the graph changed, incrementing the version (or creating the first row at
     * version 1 if none exists yet).
     *
     * @return The new version.
     */
    @Transactional
    fun bump(): Int {
        val current = repository.findTopByOrderByVersionDesc()
        if (current == null) {
            repository.save(CompetencyGraphVersion(version = 1))
            return 1
        }
        current.version += 1
        current.updatedAt = Instant.now()
        return current.version
    }
}
