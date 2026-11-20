-- ─────────────────────────────────────────────────────────────────────────────
-- V112  A CONTRIBUTOR'S CORRECTION CARRIES NO REASON
--
-- Found 2026-11-19 by the test that approves a title, location and date
-- correction with no explanation. It failed:
--
--     null value in column "reason" of relation "role_corrections"
--     violates not-null constraint (23502)
--
-- V111 removed the career editor's "How do you know?" question, so a submission
-- now arrives with no explanation. `role_corrections` is the append-only record
-- of what a correction changed, one row per field, and V97 declared its `reason`
-- NOT NULL because at the time every correction had one to give.
--
-- WHY THIS WAS INVISIBLE UNTIL NOW.
--
-- Approving an ADDITION writes no `role_corrections` row at all, so every
-- earlier explanation-free test passed. Only approving a CORRECTION of an
-- employment that already exists reaches this INSERT, and that is the common
-- case: a moderator approving a corrected title, location or dates would have
-- been handed a 500 with the whole change set unapplied, in production, with no
-- way round it from the form.
--
-- WHY NULL AND NOT A PLACEHOLDER.
--
-- The alternative was to have the applier write something non-null, such as
-- "corrected via the career editor". That stores a sentence no human wrote in
-- the column the moderation screens render as the contributor's own words, and
-- it would read like evidence. There is no reason to record, so the column says
-- so. The moderation history already guards for its absence: ModerationQueues
-- renders ` · <reason>` only when one is present.
--
-- THE NON-BLANK CHECK STAYS, and is restated rather than dropped. A NULL always
-- satisfied `btrim(reason) <> ''` because the comparison is unknown, so this is
-- the same rule; written out, the schema states that an absent reason is
-- deliberate while an empty string is still refused.
--
-- THE MODERATOR'S OWN CORRECTION TOOL IS UNAFFECTED. ModerationService still
-- requires a reason, trims it and writes it, and the admin form still refuses to
-- submit without one. That reason is an administrator's record of why they
-- overrode somebody's history and has not stopped being required.
--
-- SAFE AGAINST EXISTING DATA. Dropping NOT NULL cannot invalidate a stored row,
-- and nothing is backfilled: every reason recorded before today stays exactly as
-- it was.
--
-- RMM COMPATIBILITY (CLAUDE.md section 10). This RELAXES a rule on a shared
-- table, so no existing writer can be broken by it: an RMM build from before
-- today still sends a reason and is unaffected. Both products run the same
-- CareerChangeSetApplier and both were changed in lockstep. The only reader,
-- ModerationService's correction history, already serves the column through
-- Row.getString, which yields null without complaint.
--
-- WHY THIS IS NOT AN EDIT TO V97 OR V111. Both have been applied to development
-- databases and V97 to production. Editing an applied migration changes its
-- checksum and the next backend start refuses to boot. See V111's header and
-- migration-checksums.txt.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE role_corrections
  ALTER COLUMN reason DROP NOT NULL;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'role_correction_reason_given') THEN
    ALTER TABLE role_corrections DROP CONSTRAINT role_correction_reason_given;
  END IF;
  ALTER TABLE role_corrections
    ADD CONSTRAINT role_correction_reason_given
    CHECK (reason IS NULL OR btrim(reason) <> '');
END $$;
