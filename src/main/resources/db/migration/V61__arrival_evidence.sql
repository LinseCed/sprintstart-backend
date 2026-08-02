-- A1: some arrival steps are checked rather than taken on trust.
--
-- A0 recorded only what a hire said about themselves. This adds the other half: steps the system can
-- observe, recorded as OBSERVED rather than DECLARED, so the two are never blended into one figure.
--
-- Two derivations exist, and both are opt-in -- nothing is seeded. An admin adds the ones their
-- organisation wants from the authoring surface, which is also what keeps a "get the project
-- building" step off the board of somebody who never builds anything. The step's own `key` is what
-- binds a row to its derivation (`ArrivalDerivation`); there is deliberately no column pointing at a
-- deriver, because a column could name one that does not exist.
--
-- `self_confirmable` exists because observation and self-confirmation are not alternatives for every
-- step:
--
--   * "Your machine builds the project" can be *observed* (they authored work, so it evidently did)
--     but never *refuted* -- no contribution yet says nothing about whether the environment runs.
--     And the evidence arrives late: by the time somebody opens a pull request, getting set up is
--     days behind them. So their own word is the answer that actually lands on day one, and the
--     derivation is a backstop. Derived AND self-confirmable.
--   * "You have a GitHub account we can attribute work to" is the opposite. The check is definitive
--     when it answers at all, and letting somebody tick it would let them declare away the one fact
--     their work being credited depends on. Derived, NOT self-confirmable.
--
-- Defaults to true, which is the safe direction: a step nothing observes and nobody may tick can
-- never be settled at all, which is worse than one settled too easily.
--
-- On the user side, `github_login_verification` records what GitHub actually said about a declared
-- login. This matters more than it looks: the value is what artifact verification compares a pull
-- request's author against, so a typo does not fail loudly -- it silently stops crediting work the
-- hire really did, while leaving them reading as calm rather than blocked.
--
-- ⚠️ NULL is not "does not exist". Only a definitive 404 from GitHub records NOT_FOUND; a rate
-- limit, a 5xx or a dropped connection all leave the column alone. An outage is not evidence about
-- the world, and telling somebody their perfectly good username does not exist is worse than telling
-- them nothing. The verdict is also cleared whenever the login changes, so it never outlives the
-- value it was about.
--
-- No backfill: this workspace is dev-only and the database is wiped on each schema change, so this
-- file is the record of what changed and why, not a step to run.

ALTER TABLE arrival_steps
    ADD COLUMN self_confirmable BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE users
    ADD COLUMN github_login_verification VARCHAR(32) NULL,
    ADD COLUMN github_login_verified_at  TIMESTAMP   NULL;
