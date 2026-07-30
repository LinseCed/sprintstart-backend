package com.sprintstart.sprintstartbackend.onboarding.service

import com.sprintstart.sprintstartbackend.onboarding.model.entity.Competency
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.CreateCompetencyRequest
import com.sprintstart.sprintstartbackend.onboarding.model.request.competency.UpdateCompetencyRequest
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyGraphResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.CompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.model.response.competency.DeleteCompetencyResponse
import com.sprintstart.sprintstartbackend.onboarding.repository.CompetencyRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * Authoring the competency vocabulary: reading it, adding a competency, editing one, removing one.
 *
 * ### It is a list now, not a graph
 *
 * Prerequisite and related edges are gone, and with them traversal, versioning, pins, soft removal
 * and the whole change-replay visibility model. Those existed to serve a DAG that **gated** a hire's
 * progression and that a hire could *see*; the gates were retired, the hire-facing map was retired,
 * and what remained was read by exactly one sentence in the buddy's learning plan — a sentence that
 * could report a `RELATED` edge as a prerequisite, because the edge kind was dropped on the way out.
 *
 * So a competency is now a plain, durable name for something somebody can be proficient in. The
 * ledger keys off it, a module teaches it, and the matcher counts it. Nothing orders it.
 *
 * ### Removal is a real delete now
 *
 * Soft removal existed because visibility was replayed from change rows: deleting a row would have
 * made a hire's earned progress unresolvable. Without that model there is nothing to replay, so
 * removal deletes the row.
 *
 * Two things deliberately survive it, both keyed by the competency *key* rather than by a foreign
 * key, which is what makes this safe:
 * - **the ledger** — `user_competency_states` rows are untouched, so nobody un-earns a competency
 *   because somebody tidied the vocabulary;
 * - **its modules** — an authored module is real work and is not thrown away on a tidy-up. It simply
 *   stops appearing in the learning area until a competency with that key exists again, which
 *   re-adding it restores.
 *
 * A deleted key can currently be re-proposed by the next generation run. Making deletion **sticky**,
 * the way a dismissed board card binds the mentor, is S2 of the skill-map retirement — see
 * `forks/SKILL_MAP_RETIREMENT_DESIGN.md`.
 */
@Service
class CompetencyGraphAuthoringService(
    private val competencyRepository: CompetencyRepository,
    private val areaNormalizer: CompetencyAreaNormalizer,
) {
    /**
     * Reads one competency, so an editor can show what it currently says.
     *
     * @throws ResponseStatusException 404 if no competency has [key].
     */
    @Transactional(readOnly = true)
    fun getCompetency(key: String): CompetencyResponse = findCompetency(key).toAuthoringResponse()

    /** The whole vocabulary — what a PM authors against. */
    @Transactional(readOnly = true)
    fun getGraph(): CompetencyGraphResponse =
        CompetencyGraphResponse(
            competencies = competencyRepository.findAll().map { it.toAuthoringResponse() },
        )

    /**
     * Creates a hand-authored competency, with no AI proposal in the loop.
     *
     * This is what makes the AI genuinely optional rather than merely reviewable: a PM who wants a
     * competency the generator never suggested can add one.
     *
     * The key is slugified (see [CreateCompetencyRequest]) so it matches the house style and is
     * URL-safe.
     *
     * @throws ResponseStatusException 400 if the key or label is blank or `targetLevel` is outside
     * 1..4; 409 if a competency already has this key.
     */
    @Transactional
    fun createCompetency(request: CreateCompetencyRequest): CompetencyResponse {
        val key = slugifyKey(request.key)
        if (key.isBlank()) reject(HttpStatus.BAD_REQUEST, "key must not be blank")
        if (request.label.isBlank()) reject(HttpStatus.BAD_REQUEST, "label must not be blank")
        val targetLevel = request.targetLevel ?: Competency.DEFAULT_TARGET_LEVEL
        requireValidTargetLevel(targetLevel)
        if (competencyRepository.findByKey(key) != null) {
            reject(HttpStatus.CONFLICT, "A competency with key $key already exists")
        }

        val competency = Competency(
            key = key,
            label = request.label.trim(),
            description = request.description?.trim()?.takeIf(String::isNotBlank),
            kind = request.kind,
            area = areaNormalizer.normalize(request.area),
            targetLevel = targetLevel,
            invariant = request.invariant,
        )
        competencyRepository.save(competency)
        return competency.toAuthoringResponse()
    }

    /**
     * Applies an edit to one competency.
     *
     * Omitted fields are left alone. [key] is not editable — see [UpdateCompetencyRequest]: the
     * ledger points at it, so renaming a key would orphan everybody's progress.
     *
     * @throws ResponseStatusException 404 if no competency has [key]; 400 if `targetLevel` is
     * outside 1..4 or `label` is blank.
     */
    @Transactional
    fun updateCompetency(key: String, request: UpdateCompetencyRequest): CompetencyResponse {
        val competency = findCompetency(key)

        request.targetLevel?.let { level ->
            requireValidTargetLevel(level)
            competency.targetLevel = level
        }
        request.label?.let { label ->
            if (label.isBlank()) reject(HttpStatus.BAD_REQUEST, "label must not be blank")
            competency.label = label.trim()
        }
        // A blank description is how a PM clears one, so it maps to null rather than being rejected.
        request.description?.let { competency.description = it.trim().takeIf(String::isNotBlank) }
        request.kind?.let { competency.kind = it }
        // Blank clears the grouping, matching how a blank description clears one.
        request.area?.let { competency.area = areaNormalizer.normalize(it) }
        request.invariant?.let { competency.invariant = it }

        competencyRepository.save(competency)
        return competency.toAuthoringResponse()
    }

    /**
     * Removes a competency from the vocabulary.
     *
     * The ledger and any authored modules survive — see the class KDoc for why that is safe and what
     * it means for a module whose competency is gone.
     *
     * @throws ResponseStatusException 404 if no competency has [key].
     */
    @Transactional
    fun deleteCompetency(key: String): DeleteCompetencyResponse {
        competencyRepository.delete(findCompetency(key))
        return DeleteCompetencyResponse(key = key)
    }

    private fun findCompetency(key: String): Competency =
        competencyRepository.findByKey(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No competency found with key: $key")

    private fun requireValidTargetLevel(level: Int) {
        if (level !in MIN_TARGET_LEVEL..MAX_TARGET_LEVEL) {
            reject(
                HttpStatus.BAD_REQUEST,
                "targetLevel must be between $MIN_TARGET_LEVEL and $MAX_TARGET_LEVEL, got $level",
            )
        }
    }

    private fun reject(status: HttpStatus, message: String): Nothing = throw ResponseStatusException(status, message)

    private fun Competency.toAuthoringResponse() =
        CompetencyResponse(
            key = key,
            label = label,
            description = description,
            kind = kind,
            area = area,
            targetLevel = targetLevel,
            invariant = invariant,
        )

    private companion object {
        const val MIN_TARGET_LEVEL = 1
        const val MAX_TARGET_LEVEL = 4

        /** Kebab-cases a proposed key so hand-authored keys match the generator's house style. */
        fun slugifyKey(raw: String): String =
            raw
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
    }
}
