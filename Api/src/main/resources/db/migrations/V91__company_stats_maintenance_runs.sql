-- Alerting needs more than "a repair completed but is still wrong".
--
-- V90 made recovery automatic and recorded every discrepancy, but the only loud condition was a
-- verified-and-still-wrong repair. Three failure modes slipped straight through it:
--
--   1. THE SWEEP ERRORS OR TIMES OUT. A SQL error, a lock timeout, a connection drop. V90's
--      drift-log rows roll back with the transaction, so a failed sweep could leave no trace at all
--      and the next one simply tried again. Repeated failure looked identical to silence.
--
--   2. THE JOB STOPS RUNNING. Nothing recorded that a sweep happened, so "maintenance has not run
--      for three days" was not a question anything could answer. No application can reliably alert
--      on its own death; what it CAN do is leave a heartbeat something else - or its own next
--      startup - can check. That is what this table is.
--
--   3. DRIFT THAT KEEPS COMING BACK. A company repaired every six hours is reported as a success
--      every six hours. The individual runs are all fine and the underlying bug is invisible,
--      which is the exact shape of problem the drift log was created to expose and then did not,
--      because nobody was asking "again?".
--
-- This migration adds the heartbeat and the recurrence query. Both are read by the scheduler, which
-- now alerts on all three conditions.

CREATE TABLE IF NOT EXISTS company_stats_maintenance_runs (
    id                 BIGSERIAL   PRIMARY KEY,
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 'ok'      the sweep ran to completion (drift may or may not have been found)
    -- 'skipped' another sweep held the lock; not a problem, but it must not count as a heartbeat
    --           of WORK, only of liveness
    -- 'failed'  the sweep raised. Written by the application AFTER the sweep transaction has rolled
    --           back, which is the only way the record survives the failure it describes.
    outcome            TEXT        NOT NULL,
    drift_found        INT         NOT NULL DEFAULT 0,
    companies_repaired INT         NOT NULL DEFAULT 0,
    still_drifted      INT         NOT NULL DEFAULT 0,
    error_detail       TEXT,
    -- Which backend ran it. Both products sweep, so "RMM has been doing all the work for a week
    -- because Werkpages' timer died" is a thing worth being able to see.
    source             TEXT,
    CONSTRAINT company_stats_maintenance_runs_outcome
        CHECK (outcome IN ('ok', 'skipped', 'failed'))
);

CREATE INDEX IF NOT EXISTS company_stats_maintenance_runs_finished_idx
    ON company_stats_maintenance_runs (finished_at DESC);

-- Staleness, for a monitor or for the next startup to check.
--
-- Deliberately counts only 'ok': a long run of 'skipped' means the lock is stuck or the other
-- backend is doing everything, and treating that as healthy is how a leaked lock stays invisible.
--
-- Returns NULL when no sweep has ever completed, and the caller MUST treat that as stale.
--
-- The first version of this coalesced to '-infinity' and subtracted, which raises
-- "cannot subtract infinite timestamps". The caller caught the error and fell back to a zero age,
-- so a database where maintenance had NEVER run reported "last completed 0h ago" - healthy. A
-- check that answers "fine" when it cannot tell is worse than no check, because it is trusted.
CREATE OR REPLACE FUNCTION company_stats_maintenance_age()
RETURNS INTERVAL AS $$
    SELECT CASE WHEN MAX(finished_at) IS NULL THEN NULL ELSE now() - MAX(finished_at) END
    FROM company_stats_maintenance_runs
    WHERE outcome = 'ok';
$$ LANGUAGE sql STABLE;

-- Drift that returns AFTER a repair that was verified as successful.
--
-- The condition is specifically "repaired, then drifted again", not merely "drifted more than
-- once": a company detected three times in one sweep (three wrong columns) is one event, while a
-- company repaired on Monday and wrong again on Tuesday is a bug that is still running.
CREATE OR REPLACE FUNCTION company_stats_recurring_drift(
    p_days INT DEFAULT 7, p_min_recurrences INT DEFAULT 2)
RETURNS TABLE (company_id BIGINT, recurrences BIGINT, first_seen TIMESTAMPTZ, last_seen TIMESTAMPTZ) AS $$
    WITH events AS (
        -- One event per company per sweep, not per wrong column.
        SELECT l.company_id,
               date_trunc('second', l.detected_at) AS at,
               bool_or(l.repair_result = 'repaired') AS was_repaired
        FROM company_stats_drift_log l
        WHERE l.detected_at > now() - make_interval(days => p_days)
        GROUP BY l.company_id, date_trunc('second', l.detected_at)
    )
    SELECT e.company_id, count(*), min(e.at), max(e.at)
    FROM events e
    WHERE e.company_id IN (SELECT company_id FROM events WHERE was_repaired)
    GROUP BY e.company_id
    HAVING count(*) >= p_min_recurrences
    ORDER BY count(*) DESC, max(e.at) DESC;
$$ LANGUAGE sql STABLE;
