-- Bounds the automatic-creation rate check.
--
-- siteWideRates() backs the circuit breaker on automatic manager creation, and it runs on every
-- anonymous ghost-creation attempt. It used to count rows in anonymous_ghost_quota, which is
-- swept at 30 days and therefore stays small - but that table records only the logged-OUT path,
-- so the breaker was blind to every profile auto-created by a signed-in visitor and could never
-- trip on that half of the traffic.
--
-- The fix counted from managers WHERE approval_status = 'ghost' instead, which sees everything.
-- That set, however, grows forever: every ghost ever created stays a ghost. A per-request count
-- over an unbounded and permanently growing set is a slow leak, not a bug you notice on the day
-- you ship it.
--
-- This index makes the scan proportional to the WINDOW rather than to the history. It is partial
-- on approval_status so it holds only ghost rows, and keyed on created_at so the query can range
-- scan the last 24 hours instead of reading every ghost that has ever existed.
--
-- The query was changed in lockstep to put created_at in its WHERE clause; a FILTER inside the
-- aggregate alone cannot use an index, because every row still has to be read to be filtered.
-- Index and query only pay off together.
--
-- Shared-schema note: adding an index is backward compatible. RateMyManagers reads the same
-- table and needs no change to keep working - without this index its query is merely slower,
-- never wrong - so there is no deployment ordering hazard in either direction.

CREATE INDEX IF NOT EXISTS idx_managers_ghost_created_at
    ON managers (created_at)
    WHERE approval_status = 'ghost';
