-- Whether a rating shows the period its author worked under the manager.
--
-- WHY THIS EXISTS
--
-- A rating is anonymous by display name only. "Mar 2019 - Aug 2024" on a manager with three
-- direct reports identifies the author to that manager as precisely as a signature would, and the
-- author is the one person who can judge whether that is safe for them. Until now the dates were
-- always shown and there was no way to decline.
--
-- The column stores the author's CHOICE. The dates themselves stay in worked_from / worked_until
-- and are never cleared, because they are load-bearing elsewhere:
--
--   * the overlap check that stops one person rating the same manager twice for one period reads
--     the author's own dates back out;
--   * effectiveWorkedUntil caps the displayed end date at the date the MANAGER left the role,
--     which needs the real value to cap;
--   * career-history and company-tenure arithmetic reads them.
--
-- So this is a display rule enforced on read, not a deletion. buildReviewJson masks the dates for
-- everybody else; buildMyReviewJson keeps them, because the author's own edit form has to open
-- with what they actually stored.
--
-- WITHDRAWAL SETS IT
--
-- Deleting your own rating is a three-day soft delete: user_id is nulled immediately and the row
-- returns, anonymous, when the window expires. It returned with the dates still attached, which
-- is the one case where somebody has actively asked to be disassociated from it. ReviewRepository
-- .delete() now sets this at the same time it nulls user_id, so the row comes back dateless.
--
-- NOT NULL WITH A DEFAULT, deliberately. RateMyManagers writes to this table with its own
-- explicit column lists and does not know about this column; a NOT NULL column WITHOUT a default
-- would break every one of those INSERTs the moment this migration ran. FALSE preserves exactly
-- today's behaviour for every existing row.
ALTER TABLE reviews
    ADD COLUMN dates_hidden BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN reviews.dates_hidden IS
    'Author chose not to show the period they worked under this manager. A display rule applied on '
    'read - worked_from/worked_until keep their real values for overlap checks and tenure maths. '
    'Also set when an author withdraws their own rating, so the 3-day restore returns it dateless.';
