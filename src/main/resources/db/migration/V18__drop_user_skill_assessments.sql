-- Retires the legacy self-reported skill-assessment store. Proficiency now lives in the
-- durable competency ledger (user_competency_state), written by the adaptive assessment
-- chat and passed verifications; the skill-wizard UI that wrote this table is removed.
DROP TABLE IF EXISTS sprintstart_user_skill_assessments;
