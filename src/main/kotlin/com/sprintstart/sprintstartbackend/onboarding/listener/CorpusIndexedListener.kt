package com.sprintstart.sprintstartbackend.onboarding.listener

import com.sprintstart.sprintstartbackend.ingestion.external.events.RunIndexedEvent
import com.sprintstart.sprintstartbackend.onboarding.service.ModuleBackfillService
import com.sprintstart.sprintstartbackend.onboarding.service.StarterWorkTaskProposalService
import com.sprintstart.sprintstartbackend.onboarding.service.VocabularyGenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Turns "somebody connected a repository" into "the project has a vocabulary and material".
 *
 * The whole point of the slice: onboarding setup stops being a checklist a PM works through and
 * becomes a consequence of the corpus existing. Nothing here is approved by anyone — the gate is
 * grounding, applied inside the generator.
 *
 * ### Fire and forget, deliberately
 *
 * Generation takes model calls and minutes; a crawl must not wait on it, and a failure must not fail
 * the crawl. This mirrors `IngestionEventListener`: `AFTER_COMMIT`, then a coroutine on the
 * application scope. The consequence to accept is that a generation which dies leaves the corpus
 * indexed and the vocabulary un-regenerated until the next crawl — recoverable, and quieter than
 * rolling back an ingestion because a model was unavailable.
 *
 * ### Vocabulary, then modules, then claimable work
 *
 * A module hangs off a competency and a mined task is tagged with competency keys, so the
 * vocabulary goes first or the other two describe a vocabulary that does not exist yet. Each pass
 * is independent after that: one failing does not cancel the others, because a project with tasks
 * and no modules is more useful than a project with neither.
 *
 * ### Vocabulary first, then modules
 *
 * A module hangs off a competency, so generating modules before the competencies exist would cover
 * only what the last run happened to leave behind. The two passes are guarded differently and that
 * is intentional: the vocabulary is fingerprint-guarded and does nothing when the corpus has not
 * moved, while the module pass is guarded by "this competency has no module" and keeps chipping at
 * the backlog on every run.
 */
@Component
class CorpusIndexedListener(
    private val vocabularyGenerationService: VocabularyGenerationService,
    private val moduleBackfillService: ModuleBackfillService,
    private val starterWorkTaskProposalService: StarterWorkTaskProposalService,
    private val applicationScope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleRunIndexed(event: RunIndexedEvent) {
        applicationScope.launch {
            try {
                vocabularyGenerationService.generate()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // The module pass still runs: competencies from earlier crawls may have no material,
                // and that backlog is worth clearing even when today's generation could not.
                logger.error("Vocabulary generation failed after run {}", event.runId, e)
            }

            event.projectIds.forEach { projectId ->
                try {
                    moduleBackfillService.backfill(projectId)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    logger.error("Module backfill failed for project {}", projectId, e)
                }
            }

            try {
                // Mined tasks are live on arrival, so this is the last thing standing between
                // "a repository is connected" and "a hire has something to claim".
                starterWorkTaskProposalService.generate()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("Starter-work mining failed after run {}", event.runId, e)
            }
        }
    }
}
