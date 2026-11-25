-- ─────────────────────────────────────────────────────────────────────────────
-- V114  A REPORT CAN BE READ, AND DECIDED
--
-- Found 2026-11-23 while verifying that the Report button works as intended. It
-- does not. The button posts, the reason is validated, a duplicate is refused
-- and a row lands in `reports` - and the only SELECT against that table in
-- either backend is the duplicate check itself:
--
--     SELECT id FROM reports WHERE manager_id = $1 AND user_id = $2
--
-- No admin endpoint read it. No admin screen listed it. Nothing notified
-- anybody. The toast says "Thank you. Our team will review this profile," and
-- no team could, because there was no screen on which a report had ever been
-- visible. `reports` has existed since V1 with no status column, which is the
-- tell: nothing that drains a queue was ever built.
--
-- WHY IT MATTERS MORE THAN AN UNBUILT ADMIN SCREEN NORMALLY WOULD.
--
-- The Report button is the correction route the Terms of Service and the
-- Privacy Policy both point a rated person at. A named individual disputing
-- their own profile was being sent to a dead end, and the site thanked them for
-- it.
--
-- WHAT THIS ADDS.
--
-- `status`, so a decided report leaves the queue; `resolved_at` and
-- `resolved_by`, so a decision has an author and a time; `resolution_note`, so
-- the next admin can see what the last one concluded. The row itself is never
-- deleted: a report is evidence about a profile and about the account that
-- filed it, and both outlive the decision.
--
-- WHY STATUS IS A COLUMN AND NOT A CONVENTION.
--
-- A queue that cannot be cleared is a queue that gets ignored. Deriving
-- "handled" from anything else - a note, an edit to the manager, an admin's
-- memory - means two admins cannot tell each other what they have already
-- looked at, and the invariant belongs in the schema rather than in whichever
-- repository happens to write it (CLAUDE.md section 13).
--
-- `reports_resolution_coherent` is what makes the state honest: an open report
-- carries no decision, and a decided one carries a time. Without it, 'open'
-- with a resolved_at and 'resolved' without one are both storable, and the
-- queue would disagree with the audit trail about what happened.
--
-- WHY THERE IS NO UNIQUE INDEX ON (manager_id, user_id).
--
-- The 409 that refuses a second report from the same account is a check
-- followed by an insert, so two simultaneous submissions can both pass it. That
-- invariant does belong in the database. It is deliberately NOT added here:
-- production already holds reports, a unique index would fail to build if any
-- duplicate pair exists, and clearing one first means deleting user-submitted
-- rows, which is a destructive operation on production data and needs explicit
-- authorization (CLAUDE.md section 8). The duplicate pair is also harmless in
-- the queue, where it shows as two reports from one account. Raised separately
-- rather than decided here.
--
-- SAFE AGAINST EXISTING DATA. Every existing row takes `status = 'open'` from
-- the default, which satisfies both CHECKs, and keeps a NULL resolution. No row
-- is updated, moved or deleted.
--
-- RMM COMPATIBILITY (CLAUDE.md section 10). Three nullable columns and one NOT
-- NULL column WITH A DEFAULT, which is the one form of NOT NULL an existing
-- writer survives: RMM's INSERT names (manager_id, user_id, reason, comment)
-- and omits status, so it continues to work unchanged against this schema, and
-- a report filed by a pre-V114 build arrives 'open' and appears in the queue.
-- The CHECK constraints cannot refuse that insert, because its status comes
-- from the default and its resolution columns are null. Both products were
-- changed in lockstep anyway; neither needs the change to keep working.
--
-- WHY THIS IS NOT AN EDIT TO V1. V1 is applied everywhere, production included.
-- Editing an applied migration changes its checksum and the next backend start
-- refuses to boot. See migration-checksums.txt.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE reports
  ADD COLUMN status          TEXT NOT NULL DEFAULT 'open',
  ADD COLUMN resolved_at     TIMESTAMPTZ,
  ADD COLUMN resolved_by     UUID REFERENCES users(id) ON DELETE SET NULL,
  ADD COLUMN resolution_note TEXT;

ALTER TABLE reports
  ADD CONSTRAINT reports_status_known
  CHECK (status IN ('open', 'resolved', 'dismissed'));

-- An open report has no decision; a decided one has a time. `resolved_by` is
-- NOT required: it is ON DELETE SET NULL, so an admin account that is later
-- deleted must not retroactively invalidate a decision it made.
ALTER TABLE reports
  ADD CONSTRAINT reports_resolution_coherent
  CHECK (
    (status = 'open'  AND resolved_at IS NULL AND resolution_note IS NULL)
    OR
    (status <> 'open' AND resolved_at IS NOT NULL)
  );

-- A note is optional, and an empty one is a mistake rather than a value. The
-- 500 bound matches the limit the submit path already applies to a reporter's
-- comment, so neither end of the exchange can outgrow the other.
ALTER TABLE reports
  ADD CONSTRAINT reports_resolution_note_meaningful
  CHECK (resolution_note IS NULL
         OR (btrim(resolution_note) <> '' AND length(resolution_note) <= 500));

-- THE QUEUE READ, which is oldest-open-first and nothing else. Partial, because
-- the open set stays small while the decided set grows forever, and a full index
-- on (status, created_at) would carry every closed report for no reader.
CREATE INDEX reports_open_oldest_first
  ON reports (created_at)
  WHERE status = 'open';

-- The duplicate check and the queue's join to the manager both filter on
-- manager_id, and the FK that has pointed at managers since V1 never had an
-- index behind it. A report is also cascade-deleted with its manager, which
-- without this does a sequential scan of the whole table.
CREATE INDEX reports_manager_id ON reports (manager_id);
