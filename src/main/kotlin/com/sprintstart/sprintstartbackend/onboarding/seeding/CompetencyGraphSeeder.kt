package com.sprintstart.sprintstartbackend.onboarding.seeding

import com.sprintstart.sprintstartbackend.onboarding.external.enums.CompetencyKind
import com.sprintstart.sprintstartbackend.onboarding.external.enums.EdgeKind
import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.entity.CompetencyEdge
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyEdgeRepository
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Seeds a small development competency graph when the application starts.
 *
 * Populates a handful of grounded backend competencies and their prerequisite edges so placement,
 * projection, and (later) traversal have real data to work against locally. The runner is
 * idempotent: it only inserts a node or edge that is not already present, keyed by stable
 * competency key, so repeated restarts and partial prior seeds converge to the same graph without
 * duplicates.
 *
 * Intended for development/local setup only; gated behind `sprintstart.dev-competency-graph.enabled`.
 *
 * @property competencyRepository Repository used to check for and persist competency nodes.
 * @property competencyEdgeRepository Repository used to check for and persist prerequisite edges.
 */
@Component
@ConditionalOnProperty(
    prefix = "sprintstart.dev-competency-graph",
    name = ["enabled"],
    havingValue = "true",
)
class CompetencyGraphSeeder(
    private val competencyRepository: CompetencyRepository,
    private val competencyEdgeRepository: CompetencyEdgeRepository,
) : ApplicationRunner {
    private data class SeedNode(
        val key: String,
        val label: String,
        val kind: CompetencyKind,
    )

    private data class SeedEdge(
        val fromKey: String,
        val toKey: String,
        val kind: EdgeKind = EdgeKind.PREREQUISITE,
    )

    private val nodes = listOf(
        SeedNode("git", "Git", CompetencyKind.SKILL),
        SeedNode("kotlin", "Kotlin", CompetencyKind.SKILL),
        SeedNode("spring-boot", "Spring Boot", CompetencyKind.SKILL),
        SeedNode("our-domain-model", "Our Domain Model", CompetencyKind.CONCEPT),
        SeedNode("jpa-persistence", "JPA Persistence", CompetencyKind.CONCEPT),
        SeedNode("sse-streaming", "SSE Streaming", CompetencyKind.CONCEPT),
        SeedNode("security-policy", "Security Policy", CompetencyKind.POLICY),
    )

    // "to <- from": the target competency requires the source competency first.
    private val edges = listOf(
        SeedEdge(fromKey = "kotlin", toKey = "our-domain-model"),
        SeedEdge(fromKey = "spring-boot", toKey = "our-domain-model"),
        SeedEdge(fromKey = "our-domain-model", toKey = "jpa-persistence"),
        SeedEdge(fromKey = "our-domain-model", toKey = "sse-streaming"),
    )

    /**
     * Inserts any missing seed nodes and edges after the application context has started.
     *
     * Each node is created only when no competency with its stable key exists; each edge is
     * created only when no edge with the same (fromKey, toKey, kind) triple exists. Existing rows
     * are left untouched so the seeder never overwrites hand-edited or previously seeded data.
     *
     * @param args Application startup arguments provided by Spring Boot.
     */
    override fun run(args: ApplicationArguments) {
        nodes.forEach { node ->
            if (!competencyRepository.existsByKey(node.key)) {
                competencyRepository.save(
                    Competency(key = node.key, label = node.label, kind = node.kind),
                )
            }
        }

        edges.forEach { edge ->
            val exists = competencyEdgeRepository.existsByFromKeyAndToKeyAndKind(
                edge.fromKey,
                edge.toKey,
                edge.kind,
            )
            if (!exists) {
                competencyEdgeRepository.save(
                    CompetencyEdge(fromKey = edge.fromKey, toKey = edge.toKey, kind = edge.kind),
                )
            }
        }
    }
}
