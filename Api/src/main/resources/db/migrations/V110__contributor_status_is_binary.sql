-- ─────────────────────────────────────────────────────────────────────────────
-- V110  A CONTRIBUTOR SAYS "IN THIS ROLE" OR "NOT IN THIS ROLE". NOTHING ELSE.
--
-- Requested 2026-11-17, while V109 was still undeployed but already applied to a
-- development database.
--
-- WHY THIS IS NOT AN EDIT TO V109.
--
-- V109 shipped `new_employment_status IN ('ongoing','ended','unknown')`. Narrowing
-- it in place changed that file's checksum, and the next backend start refused:
--
--     Migration checksum mismatch for migration version 109
--
-- which is the whole reason this project never edits a migration that has run
-- anywhere. The rule is not weaker for a development database; it is simply
-- cheaper to notice there. So the narrowing is its own version.
--
-- WHAT CHANGES, AND WHAT DOES NOT.
--
-- `manager_edit_employments.new_employment_status` is what a CONTRIBUTOR PROPOSES
-- about somebody else's job. From now on that is 'ongoing' or 'ended'.
--
-- "I'm not sure" produced a row with no stated end and no stated dates, and every
-- display surface then had to guess what to do with it. The guesses disagreed: the
-- trajectory ordered undated roles last, the manager detail ordered them first,
-- and the editor put "they left, date unknown" above a role that ended last year.
-- An older role appeared to be the newest because somebody did not know a date.
--
-- `career_history.employment_status` KEEPS all four values and is untouched:
--
--   * 'unknown' is that column's DEFAULT and the state of nearly every row
--     written before Rev 268, so forbidding it would invalidate real history;
--   * 'disputed' is a moderation outcome reached when accounts contradict each
--     other, which no single contributor may assert.
--
-- Those rows stay readable, displayable and correctable. They are simply never
-- what somebody proposes.
--
-- RMM COMPATIBILITY (CLAUDE.md section 10): this NARROWS a CHECK, so it is the
-- one shape that can fail on existing rows. Verified safe because the column is
-- new in V109, V109 is not deployed anywhere, and no row carrying 'unknown' can
-- exist yet - the guard below proves it rather than assuming it. Both products'
-- application code already refuses 'unknown' before it reaches the database.
--
-- Idempotent: the constraint is dropped if present and recreated.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. PROVE THERE IS NOTHING TO BREAK ──────────────────────────────────────
--
-- A narrowing CHECK is validated against existing rows, so it fails loudly if any
-- violate it. That is the correct behaviour and a confusing way to find out, so
-- this says plainly what the problem would be before the ALTER reaches it.

DO $$
DECLARE offending BIGINT;
BEGIN
  SELECT count(*) INTO offending
    FROM manager_edit_employments
   WHERE new_employment_status IS NOT NULL
     AND new_employment_status NOT IN ('ongoing', 'ended');

  IF offending > 0 THEN
    RAISE EXCEPTION
      'V110 cannot narrow new_employment_status: % pending change-set row(s) still '
      'propose a status outside (ongoing, ended). Those contributions were written '
      'against the older rule and have to be decided or withdrawn first; narrowing '
      'around them would leave proposals in the queue that can never be applied.',
      offending;
  END IF;
END $$;


-- ── 2. THE NARROWING ────────────────────────────────────────────────────────

ALTER TABLE manager_edit_employments
  DROP CONSTRAINT IF EXISTS edit_employment_status_known;

ALTER TABLE manager_edit_employments
  ADD CONSTRAINT edit_employment_status_known
  CHECK (new_employment_status IS NULL
         OR new_employment_status IN ('ongoing', 'ended'));


-- ── 3. career_history IS DELIBERATELY UNTOUCHED ─────────────────────────────
--
-- Stated here because its absence is the kind of thing a later reader assumes was
-- an oversight. employment_status_known still admits
-- ('ongoing','ended','unknown','disputed') and must: 'unknown' is the column
-- default and the state of history written before Rev 268, and 'disputed' is how
-- moderation records that two accounts contradict each other. Narrowing it would
-- make existing rows unwritable and take the moderation outcome away with them.
