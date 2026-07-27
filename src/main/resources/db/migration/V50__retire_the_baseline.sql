-- Retire the baseline.
--
-- A blueprint was the shared, mandatory, PM-owned half of the original onboarding design: the
-- competencies a project expects of every hire, which a per-user path was then projected against.
-- The path stopped reading it when it became goal-directed -- `CompetencyPathService` takes its
-- targets from the competency the hire claimed, and from nothing else.
--
-- What was left is worse than dead code. The setup ladder still had a rung telling a PM to choose a
-- baseline, and a page to choose it on, and nothing anywhere read the answer. Dead code costs a
-- reader; dead *work* costs the person doing it, and looks like progress while they do it. That is
-- what this removes.
--
-- Nothing a hire earned lived here. Progress is `user_competency_states`, and verification history
-- is `verification_attempts`; both are untouched, as they were by every rework before this one --
-- the ledger is the thing that survives, which is the whole reason it was separated from the path.
-- `blueprint_competencies` held a selection and a review status, never anybody's progress.
--
-- Dropped children-first; `blueprint_competencies` references `blueprints`.
DROP TABLE IF EXISTS blueprint_competencies;
DROP TABLE IF EXISTS blueprints;
