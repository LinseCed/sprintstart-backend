-- Onboarding goes per-project (backend #37). Onboarding was global-per-user and structurally
-- single-project: onboarding_paths.user_id was unique (one path per user, ever) and blueprints
-- carried no project. This scopes blueprints and paths to a project, while the competency ledger
-- (user_competency_state) and catalog stay global -- a proven skill transfers across projects.
--
-- NOTE: like the other onboarding tables, blueprints / blueprint_steps / onboarding_paths are
-- built by Hibernate ddl-auto in dev; this migration documents the delta for prod. How prod
-- applies migrations is tracker #12 open question 4. Kept in sync with the JPA entities
-- Blueprint / BlueprintStep / OnboardingPath.

-- 1. A blueprint belongs to a project. Nullable: legacy/global blueprints keep a null project and
--    act as the fallback baseline for projects that have none of their own yet.
ALTER TABLE blueprints
    ADD COLUMN IF NOT EXISTS project_id UUID;

-- 2. Blueprint step -> competency bridge. Nullable: the AI does not emit competency keys yet, and
--    a project whose steps declare no keys falls back to targeting all visible competencies.
ALTER TABLE blueprint_steps
    ADD COLUMN IF NOT EXISTS competency_key VARCHAR(255);

-- 3. A path belongs to a project; a user has at most one path per project.
ALTER TABLE onboarding_paths
    ADD COLUMN IF NOT EXISTS project_id UUID;

-- Backfill: assign each existing path to the user's project when they belong to exactly one.
UPDATE onboarding_paths p
SET project_id = (
    SELECT up.project_id FROM user_projects up WHERE up.user_id = p.user_id
)
WHERE p.project_id IS NULL
  AND (SELECT COUNT(*) FROM user_projects up WHERE up.user_id = p.user_id) = 1;

-- Paths still unassigned (user in zero or several projects) cannot be resolved automatically.
-- The path is a disposable projection, so drop the un-assignable ones; they regenerate per project
-- on the next personalization. The multi-membership backfill rule is tracker #12 open question 3.
DELETE FROM onboarding_paths WHERE project_id IS NULL;

-- Enforce the per-project shape.
ALTER TABLE onboarding_paths
    ALTER COLUMN project_id SET NOT NULL;

-- Drop the old single-path-per-user uniqueness on user_id (created by ddl-auto with a generated
-- name), then add the composite (user_id, project_id) uniqueness.
DO $$
DECLARE
    old_constraint text;
BEGIN
    SELECT conname INTO old_constraint
    FROM pg_constraint
    WHERE conrelid = 'onboarding_paths'::regclass
      AND contype = 'u'
      AND array_length(conkey, 1) = 1
      AND conkey[1] = (
          SELECT attnum FROM pg_attribute
          WHERE attrelid = 'onboarding_paths'::regclass AND attname = 'user_id'
      );
    IF old_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE onboarding_paths DROP CONSTRAINT %I', old_constraint);
    END IF;
END $$;

ALTER TABLE onboarding_paths
    DROP CONSTRAINT IF EXISTS uq_onboarding_paths_user_project;

ALTER TABLE onboarding_paths
    ADD CONSTRAINT uq_onboarding_paths_user_project UNIQUE (user_id, project_id);
