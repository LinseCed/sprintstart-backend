-- Which candidate competency keys each interview question set out to probe.
--
-- The AI service is stateless and re-derives its belief from the transcript on every turn, but a
-- question is prose: the mapping from that prose back to competency keys exists only in the
-- response that produced it. Without it the interviewer can count turns but cannot tell which
-- candidates it has actually asked about -- which is why a "complete beginner" interview could
-- finish having probed 2 of 10 competencies.
--
-- Accumulated turn by turn on the caller side and sent with every request, for the same reason
-- candidate_signal is: the service holds no session state of its own.
--
-- Existing sessions simply have no rows here. An in-flight interview therefore reads as "nothing
-- probed yet"; it will over-cover rather than under-cover, which is the safe direction.

CREATE TABLE IF NOT EXISTS skill_assessment_turn_targets
(
    turn_id        UUID NOT NULL REFERENCES skill_assessment_turns (id) ON DELETE CASCADE,
    competency_key TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_skill_assessment_turn_targets_turn
    ON skill_assessment_turn_targets (turn_id);
