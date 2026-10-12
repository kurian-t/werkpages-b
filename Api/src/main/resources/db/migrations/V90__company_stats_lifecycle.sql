-- The complete company_stats_live lifecycle, owned by this database.
--
-- V39 created the table, V47 put manager aggregates behind a trigger, V79 added four columns and
-- taught only the application to fill them, V89 moved all nine columns into the trigger and deleted
-- the application writers. What V89 left behind was the rest of the lifecycle: recovery from drift
-- still needed a human, the repair tool contained its own (wrong) copy of the maths, and bulk
-- writes recomputed one company once per affected row.
--
-- This migration makes the whole lifecycle automatic and gives it exactly one implementation.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- WHEN DOES A PROJECTION ROW EXIST?
-- ─────────────────────────────────────────────────────────────────────────────
--
-- ONE ROW PER COMPANY WHOSE status <> 'merged'. Zeros where there is no data.
--
-- Stated here because it was previously implicit and inconsistent, and the inconsistency destroyed
-- data: V47 deleted a row when no qualifying MANAGERS remained, which was right when managers were
-- the only dataset and wrong once workplace and interview ratings existed. A company rated as a
-- workplace with no managers listed had its row - and its counts - deleted on the next recompute.
--
-- "Row per company" is safe because no reader depends on a row being ABSENT. Every consumer either
-- LEFT JOINs it (suggestCompanies, findBySlug, findCompanyParent, the redirect lookup) or filters
-- on manager_count > 0 (findCompanyListing, findCompaniesByIndustry, findUnclassified). An all-zero
-- row is therefore invisible to the directory and harmless everywhere else, and CLAUDE.md section
-- 22's rule that the listing shows only companies with >=1 approved/ghost manager is preserved by
-- that same filter rather than by the absence of a row.
--
-- It also removes a whole class of bug: there is no longer any "is this an orphan" judgement to get
-- wrong in three places. A row is removed only when its company stops existing (the FK is
-- ON DELETE CASCADE) or is merged away.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- CONCURRENCY
-- ─────────────────────────────────────────────────────────────────────────────
--
-- Every maintenance path - trigger, automatic repair, admin action - goes through
-- company_stats_apply(), and that function takes a TRANSACTION-SCOPED ADVISORY LOCK PER COMPANY
-- BEFORE IT READS THE SOURCE TABLES.
--
-- Reading before locking is the subtle failure, and it is why "it is in a transaction" proves
-- nothing here. A repair that computed its numbers and then serialized only the final upsert could
-- still overwrite a newer committed value: it read the source at T1, a rating was inserted and
-- committed at T2, and the repair wrote its T1 numbers at T3. Taking the lock first makes the
-- read-and-write pair atomic with respect to any other maintenance of that company, so the last
-- writer is always the one that read last.
--
-- Why an ADVISORY lock rather than SELECT ... FOR UPDATE on the projection row: the row may not
-- exist. The case that matters most - a missing projection row, which is precisely what repair is
-- for - has nothing to lock. An advisory key derived from company_id exists whether the row does
-- or not.
--
-- DEADLOCK ORDER: the id array is sorted ascending before locking, so two transactions touching the
-- same pair of companies always take those locks in the same order. This matters for company
-- reassignment, which by definition touches two companies at once.
--
-- RETRIES: the function holds no state and the upsert is idempotent, so a transaction that fails
-- (serialization failure, deadlock, lock timeout) can simply be run again. Callers retry the batch.
--
-- The source-visibility rule for the two caller kinds:
--   * TRIGGERS run inside the source transaction, so they already see the change that fired them,
--     and their lock is released when that transaction commits. A reader never sees a projection
--     that disagrees with a committed source, because both become visible at the same commit.
--   * REPAIR runs in its own transaction per batch at READ COMMITTED. Holding the per-company
--     advisory lock across its read and its write means any live write to that company either
--     finished before the read or starts after the write.

-- ═════════════════════════════════════════════════════════════════════════════
-- 1. THE CANONICAL CALCULATION - one definition, used by everything
-- ═════════════════════════════════════════════════════════════════════════════
--
-- A set-returning function so that the recompute AND the drift check consume the same arithmetic.
-- Two copies of this is the bug that has recurred three times now: V79 against V47's trigger, then
-- CompanyStatsRebuilder against V89's trigger.
--
-- EACH DATASET IS AGGREGATED INDEPENDENTLY, then combined. Joining managers, company_reviews and
-- interview_reviews in one FROM multiplies the rows out and every COUNT and AVG inflates: a company
-- with 2 managers and 3 workplace ratings would report 6 of each. The managers side is a LEFT JOIN
-- with its filters in the ON clause (so a company with no qualifying managers still produces a
-- row), and the other two are correlated scalar subqueries, which cannot fan out.
--
-- p_company_ids NULL means "every company", for the full rebuild.
CREATE OR REPLACE FUNCTION company_stats_expected(p_company_ids BIGINT[] DEFAULT NULL)
RETURNS TABLE (
    company_id           BIGINT,
    manager_count        BIGINT,
    total_reviews        BIGINT,
    avg_rating           NUMERIC(3,1),
    logo_url             TEXT,
    workplace_count      BIGINT,
    workplace_avg_rating NUMERIC(3,1),
    interview_count      BIGINT,
    interview_avg_rating NUMERIC(3,1)
) AS $$
    SELECT c.id,
           COUNT(DISTINCT m.id)::BIGINT,
           COALESCE(SUM(m.reviews_count), 0)::BIGINT,
           -- The average MANAGER rating at this company. Distinct from workplace_avg_rating (the
           -- average rating OF the company) and interview_avg_rating. Three different questions.
           -- Only managers who actually have reviews count, or an unrated manager would drag the
           -- company's figure toward whatever default their row carries.
           ROUND(AVG(m.overall_rating) FILTER (
                 WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0)::NUMERIC, 1)::NUMERIC(3,1),
           -- Prefer a logo.dev URL from a manager row, then the company's own, then any manager's.
           -- Unchanged from V47/V89 - this is a display preference, not an aggregate.
           COALESCE(
               MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
               c.logo_url,
               MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
           (SELECT COUNT(*) FROM company_reviews cr
             WHERE cr.company_id = c.id AND cr.deleted_at IS NULL)::BIGINT,
           (SELECT ROUND(AVG(cr.overall_rating)::NUMERIC, 1) FROM company_reviews cr
             WHERE cr.company_id = c.id AND cr.deleted_at IS NULL)::NUMERIC(3,1),
           (SELECT COUNT(*) FROM interview_reviews ir
             WHERE ir.company_id = c.id AND ir.deleted_at IS NULL)::BIGINT,
           (SELECT ROUND(AVG(ir.overall_rating)::NUMERIC, 1) FROM interview_reviews ir
             WHERE ir.company_id = c.id AND ir.deleted_at IS NULL)::NUMERIC(3,1)
    FROM companies c
    LEFT JOIN managers m ON m.company_id = c.id
        AND m.approval_status IN ('approved', 'ghost')
        AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
    WHERE c.status <> 'merged'
      AND (p_company_ids IS NULL OR c.id = ANY (p_company_ids))
    GROUP BY c.id, c.logo_url;
$$ LANGUAGE sql STABLE;

-- ═════════════════════════════════════════════════════════════════════════════
-- 2. THE ONE MAINTENANCE ENTRY POINT
-- ═════════════════════════════════════════════════════════════════════════════
--
-- Recomputes the given companies. Returns how many projection rows it wrote.
--
-- NULL means every company, which is the full rebuild; there is no second rebuild implementation.
CREATE OR REPLACE FUNCTION company_stats_apply(p_company_ids BIGINT[] DEFAULT NULL)
RETURNS BIGINT AS $$
DECLARE
    v_ids     BIGINT[];
    v_id      BIGINT;
    v_written BIGINT := 0;
BEGIN
    IF p_company_ids IS NULL THEN
        -- Full rebuild. Lock ordering still matters, so the ids are taken in ascending order.
        SELECT array_agg(id ORDER BY id) INTO v_ids FROM companies WHERE status <> 'merged';
    ELSE
        -- Deduplicate AND sort: dedup keeps a bulk statement from recomputing one company once per
        -- affected row, and the ascending order gives every caller the same lock sequence.
        SELECT array_agg(DISTINCT x ORDER BY x) INTO v_ids
        FROM unnest(p_company_ids) AS x
        WHERE x IS NOT NULL;
    END IF;

    IF v_ids IS NULL OR cardinality(v_ids) = 0 THEN
        RETURN 0;
    END IF;

    FOREACH v_id IN ARRAY v_ids LOOP
        -- BEFORE the source read. See the concurrency note at the top of this file.
        PERFORM pg_advisory_xact_lock(847231, (v_id % 2147483647)::INT);

        -- A merged company, or one that has gone, keeps no projection row. This DELETE targets
        -- company_stats_live and nothing else: no source company, manager or rating is ever removed
        -- by maintenance.
        IF NOT EXISTS (SELECT 1 FROM companies WHERE id = v_id AND status <> 'merged') THEN
            DELETE FROM company_stats_live WHERE company_id = v_id;
            CONTINUE;
        END IF;

        INSERT INTO company_stats_live AS cs
               (company_id, manager_count, total_reviews, avg_rating, logo_url,
                workplace_count, workplace_avg_rating, interview_count, interview_avg_rating,
                updated_at)
        SELECT e.company_id, e.manager_count, e.total_reviews, e.avg_rating, e.logo_url,
               e.workplace_count, e.workplace_avg_rating, e.interview_count, e.interview_avg_rating,
               now()
        FROM company_stats_expected(ARRAY[v_id]) e
        ON CONFLICT (company_id) DO UPDATE SET
            manager_count        = EXCLUDED.manager_count,
            total_reviews        = EXCLUDED.total_reviews,
            avg_rating           = EXCLUDED.avg_rating,
            logo_url             = EXCLUDED.logo_url,
            workplace_count      = EXCLUDED.workplace_count,
            workplace_avg_rating = EXCLUDED.workplace_avg_rating,
            interview_count      = EXCLUDED.interview_count,
            interview_avg_rating = EXCLUDED.interview_avg_rating,
            updated_at           = now();

        v_written := v_written + 1;
    END LOOP;

    RETURN v_written;
END;
$$ LANGUAGE plpgsql;

-- Kept so that nothing which already calls the V47/V89 name has to change, and so there is still
-- only one implementation beneath it.
CREATE OR REPLACE FUNCTION refresh_company_stats(p_company_id BIGINT) RETURNS void AS $$
BEGIN
    IF p_company_id IS NOT NULL THEN
        PERFORM company_stats_apply(ARRAY[p_company_id]);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ═════════════════════════════════════════════════════════════════════════════
-- 3. TRIGGERS - per STATEMENT, not per row
-- ═════════════════════════════════════════════════════════════════════════════
--
-- V47 and V89 used FOR EACH ROW, which recomputes a company once per affected row. An admin action
-- that reassigns 500 managers from one company to another did 1000 recomputes of two companies.
--
-- These use transition tables (REFERENCING ... TABLE), collect the affected company ids once per
-- statement, and hand the whole set to company_stats_apply, which deduplicates. One statement
-- touching 500 managers of one company now performs one recompute.
--
-- PostgreSQL does not allow transition tables on a trigger with multiple events, so each table
-- needs one trigger per event. The aliases are always new_rows / old_rows so that the three
-- functions below can be shared by every source table that has a company_id column.

CREATE OR REPLACE FUNCTION company_stats_from_new_rows() RETURNS trigger AS $$
DECLARE v_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT company_id) INTO v_ids FROM new_rows WHERE company_id IS NOT NULL;
    PERFORM company_stats_apply(v_ids);
    RETURN NULL;  -- AFTER ... FOR EACH STATEMENT ignores the return value.
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION company_stats_from_old_rows() RETURNS trigger AS $$
DECLARE v_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT company_id) INTO v_ids FROM old_rows WHERE company_id IS NOT NULL;
    PERFORM company_stats_apply(v_ids);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- BOTH sides, because a record moving between companies leaves the company it left stale. This is
-- the reassignment case, and it is why the lock order in company_stats_apply is deterministic:
-- this statement locks two companies at once.
CREATE OR REPLACE FUNCTION company_stats_from_both_rows() RETURNS trigger AS $$
DECLARE v_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT id) INTO v_ids FROM (
        SELECT company_id AS id FROM new_rows WHERE company_id IS NOT NULL
        UNION
        SELECT company_id AS id FROM old_rows WHERE company_id IS NOT NULL
    ) AS affected;
    PERFORM company_stats_apply(v_ids);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- A column list CANNOT be combined with transition tables: PostgreSQL rejects
-- "AFTER UPDATE OF col ... REFERENCING OLD TABLE" with
--   "transition tables cannot be specified for triggers with column lists".
--
-- So the UPDATE triggers fire on every update and the column filter moves INSIDE the function,
-- comparing old_rows to new_rows. That is a deliberate trade: the trigger is now entered for an
-- edit that cannot affect these aggregates (a manager's title, say), but it does one join over the
-- transition tables, finds nothing changed, and returns without touching the projection. Paying a
-- cheap no-op per statement buys the per-statement deduplication, which is worth far more: the
-- previous row-level triggers recomputed one company once per affected row.
--
-- Each UPDATE function collects BOTH the old and the new company_id of every row whose relevant
-- columns changed, so a record moving between companies refreshes the company it left as well as
-- the one it joined.

-- ── managers ─────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION managers_company_stats_update() RETURNS trigger AS $$
DECLARE v_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT cid) INTO v_ids FROM (
        SELECT n.company_id AS cid
        FROM new_rows n JOIN old_rows o ON o.id = n.id
        WHERE ROW(o.company_id, o.approval_status, o.external_id,
                  o.reviews_count, o.overall_rating, o.company_logo_url)
           IS DISTINCT FROM
              ROW(n.company_id, n.approval_status, n.external_id,
                  n.reviews_count, n.overall_rating, n.company_logo_url)
        UNION
        SELECT o.company_id
        FROM new_rows n JOIN old_rows o ON o.id = n.id
        WHERE ROW(o.company_id, o.approval_status, o.external_id,
                  o.reviews_count, o.overall_rating, o.company_logo_url)
           IS DISTINCT FROM
              ROW(n.company_id, n.approval_status, n.external_id,
                  n.reviews_count, n.overall_rating, n.company_logo_url)
    ) AS affected
    WHERE cid IS NOT NULL;

    IF v_ids IS NOT NULL AND cardinality(v_ids) > 0 THEN
        PERFORM company_stats_apply(v_ids);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS managers_company_stats     ON managers;
DROP TRIGGER IF EXISTS managers_company_stats_ins ON managers;
DROP TRIGGER IF EXISTS managers_company_stats_upd ON managers;
DROP TRIGGER IF EXISTS managers_company_stats_del ON managers;

CREATE TRIGGER managers_company_stats_ins AFTER INSERT ON managers
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION company_stats_from_new_rows();

CREATE TRIGGER managers_company_stats_upd AFTER UPDATE ON managers
REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION managers_company_stats_update();

CREATE TRIGGER managers_company_stats_del AFTER DELETE ON managers
REFERENCING OLD TABLE AS old_rows
FOR EACH STATEMENT EXECUTE FUNCTION company_stats_from_old_rows();

-- ── company_reviews (workplace ratings) and interview_reviews ────────────────
--
-- One function for both: they are the same shape for this purpose. deleted_at is in the comparison
-- because a withdrawal is an UPDATE, not a DELETE, and it changes both the count and the average.
CREATE OR REPLACE FUNCTION rating_company_stats_update() RETURNS trigger AS $$
DECLARE v_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT cid) INTO v_ids FROM (
        SELECT n.company_id AS cid
        FROM new_rows n JOIN old_rows o ON o.id = n.id
        WHERE ROW(o.company_id, o.overall_rating, o.deleted_at)
           IS DISTINCT FROM ROW(n.company_id, n.overall_rating, n.deleted_at)
        UNION
        SELECT o.company_id
        FROM new_rows n JOIN old_rows o ON o.id = n.id
        WHERE ROW(o.company_id, o.overall_rating, o.deleted_at)
           IS DISTINCT FROM ROW(n.company_id, n.overall_rating, n.deleted_at)
    ) AS affected
    WHERE cid IS NOT NULL;

    IF v_ids IS NOT NULL AND cardinality(v_ids) > 0 THEN
        PERFORM company_stats_apply(v_ids);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS company_reviews_company_stats     ON company_reviews;
DROP TRIGGER IF EXISTS company_reviews_company_stats_ins ON company_reviews;
DROP TRIGGER IF EXISTS company_reviews_company_stats_upd ON company_reviews;
DROP TRIGGER IF EXISTS company_reviews_company_stats_del ON company_reviews;

CREATE TRIGGER company_reviews_company_stats_ins AFTER INSERT ON company_reviews
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION company_stats_from_new_rows();

CREATE TRIGGER company_reviews_company_stats_upd AFTER UPDATE ON company_reviews
REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION rating_company_stats_update();

CREATE TRIGGER company_reviews_company_stats_del AFTER DELETE ON company_reviews
REFERENCING OLD TABLE AS old_rows
FOR EACH STATEMENT EXECUTE FUNCTION company_stats_from_old_rows();

DROP TRIGGER IF EXISTS interview_reviews_company_stats     ON interview_reviews;
DROP TRIGGER IF EXISTS interview_reviews_company_stats_ins ON interview_reviews;
DROP TRIGGER IF EXISTS interview_reviews_company_stats_upd ON interview_reviews;
DROP TRIGGER IF EXISTS interview_reviews_company_stats_del ON interview_reviews;

CREATE TRIGGER interview_reviews_company_stats_ins AFTER INSERT ON interview_reviews
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION company_stats_from_new_rows();

CREATE TRIGGER interview_reviews_company_stats_upd AFTER UPDATE ON interview_reviews
REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION rating_company_stats_update();

CREATE TRIGGER interview_reviews_company_stats_del AFTER DELETE ON interview_reviews
REFERENCING OLD TABLE AS old_rows
FOR EACH STATEMENT EXECUTE FUNCTION company_stats_from_old_rows();

-- ── companies (metadata, and the row-existence rule) ─────────────────────────
--
-- Three dependencies, not one:
--   * INSERT   - the rule is a row per non-merged company, so a new company gets its zero row at
--                once rather than waiting for its first manager or rating.
--   * logo_url - feeds the projection's logo preference.
--   * status   - a company becoming 'merged' must lose its row, and one coming back must regain it.
CREATE OR REPLACE FUNCTION companies_company_stats_insert() RETURNS trigger AS $$
DECLARE v_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT id) INTO v_ids FROM new_rows WHERE id IS NOT NULL;
    IF v_ids IS NOT NULL AND cardinality(v_ids) > 0 THEN
        PERFORM company_stats_apply(v_ids);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION companies_company_stats_update() RETURNS trigger AS $$
DECLARE v_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT n.id) INTO v_ids
    FROM new_rows n JOIN old_rows o ON o.id = n.id
    WHERE ROW(o.logo_url, o.status) IS DISTINCT FROM ROW(n.logo_url, n.status)
      AND n.id IS NOT NULL;

    IF v_ids IS NOT NULL AND cardinality(v_ids) > 0 THEN
        PERFORM company_stats_apply(v_ids);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS companies_company_stats     ON companies;
DROP TRIGGER IF EXISTS companies_company_stats_ins ON companies;
DROP TRIGGER IF EXISTS companies_company_stats_upd ON companies;

CREATE TRIGGER companies_company_stats_ins AFTER INSERT ON companies
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION companies_company_stats_insert();

CREATE TRIGGER companies_company_stats_upd AFTER UPDATE ON companies
REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION companies_company_stats_update();

-- ═════════════════════════════════════════════════════════════════════════════
-- 4. DRIFT: EVIDENCE, DETECTION, AUTOMATIC REPAIR
-- ═════════════════════════════════════════════════════════════════════════════
--
-- A repair that leaves no trace hides a recurring bug. If the same company drifts every six hours,
-- the numbers are right after each run and something is still broken - and with no record, the only
-- symptom is numbers that are always correct when anybody looks.
CREATE TABLE IF NOT EXISTS company_stats_drift_log (
    id            BIGSERIAL PRIMARY KEY,
    company_id    BIGINT      NOT NULL,
    field         TEXT        NOT NULL,
    expected      TEXT,
    actual        TEXT,
    -- 'missing_row' and 'obsolete_row' are kinds of drift in their own right, not just wrong
    -- numbers, and they have different causes worth telling apart.
    drift_kind    TEXT        NOT NULL DEFAULT 'value',
    detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Filled by the repair pass: 'repaired', 'failed', or 'unverified' when the re-check still
    -- disagreed. A row left 'pending' means the process died between detection and repair, which
    -- is itself worth seeing.
    repair_result TEXT        NOT NULL DEFAULT 'pending',
    repaired_at   TIMESTAMPTZ,
    detail        TEXT,
    -- No FK to companies: the evidence must outlive a company that gets deleted or merged, since
    -- "the row was obsolete" is exactly the finding in that case.
    CONSTRAINT company_stats_drift_log_kind CHECK (drift_kind IN ('value', 'missing_row', 'obsolete_row')),
    CONSTRAINT company_stats_drift_log_result CHECK (repair_result IN ('pending', 'repaired', 'failed', 'unverified'))
);

-- Recurrence is the question this table is asked most: "has this company drifted before?"
CREATE INDEX IF NOT EXISTS company_stats_drift_log_company_idx
    ON company_stats_drift_log (company_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS company_stats_drift_log_detected_idx
    ON company_stats_drift_log (detected_at DESC);

-- ── Detection ────────────────────────────────────────────────────────────────
--
-- Expected against stored, for every company, in ONE statement - so every comparison sees the same
-- snapshot. Comparing per company in a loop would let a rating committed halfway through the run
-- show up as drift that never existed.
--
-- A FULL OUTER JOIN, because three different things are wrong:
--   * both sides present, numbers differ      -> 'value'
--   * expected present, no stored row         -> 'missing_row'
--   * stored row, company merged or gone      -> 'obsolete_row'
--
-- Counts and the logo only. An average that disagrees always has a count that disagrees behind it,
-- and comparing rounded numerics invites a false alarm over the last decimal place - a checker that
-- cries wolf gets switched off. The logo IS compared, because it is the one non-aggregate field and
-- a stale logo is visible on every tile.
CREATE OR REPLACE FUNCTION company_stats_detect_drift(p_limit INT DEFAULT 1000)
RETURNS TABLE (company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT) AS $$
    WITH expected AS (
        SELECT * FROM company_stats_expected(NULL)
    ),
    compared AS (
        SELECT COALESCE(e.company_id, cs.company_id) AS cid,
               e.company_id IS NULL                 AS obsolete,
               cs.company_id IS NULL                AS missing,
               e.manager_count, cs.manager_count   AS a_manager_count,
               e.total_reviews, cs.total_reviews   AS a_total_reviews,
               e.workplace_count, cs.workplace_count AS a_workplace_count,
               e.interview_count, cs.interview_count AS a_interview_count,
               e.logo_url,      cs.logo_url        AS a_logo_url
        FROM expected e
        FULL OUTER JOIN company_stats_live cs ON cs.company_id = e.company_id
    )
    SELECT cid, field, expected, actual, kind FROM (
        SELECT cid, 'row' AS field, 'present' AS expected, 'absent' AS actual,
               'missing_row' AS kind, 0 AS ord
        FROM compared WHERE missing
        UNION ALL
        SELECT cid, 'row', 'absent', 'present', 'obsolete_row', 1
        FROM compared WHERE obsolete
        UNION ALL
        SELECT cid, 'manager_count', manager_count::TEXT, a_manager_count::TEXT, 'value', 2
        FROM compared WHERE NOT missing AND NOT obsolete AND manager_count <> a_manager_count
        UNION ALL
        SELECT cid, 'total_reviews', total_reviews::TEXT, a_total_reviews::TEXT, 'value', 3
        FROM compared WHERE NOT missing AND NOT obsolete AND total_reviews <> a_total_reviews
        UNION ALL
        SELECT cid, 'workplace_count', workplace_count::TEXT, a_workplace_count::TEXT, 'value', 4
        FROM compared WHERE NOT missing AND NOT obsolete AND workplace_count <> a_workplace_count
        UNION ALL
        SELECT cid, 'interview_count', interview_count::TEXT, a_interview_count::TEXT, 'value', 5
        FROM compared WHERE NOT missing AND NOT obsolete AND interview_count <> a_interview_count
        UNION ALL
        SELECT cid, 'logo_url', logo_url, a_logo_url, 'value', 6
        FROM compared WHERE NOT missing AND NOT obsolete AND logo_url IS DISTINCT FROM a_logo_url
    ) AS drift
    ORDER BY cid, ord
    LIMIT p_limit;
$$ LANGUAGE sql STABLE;

-- ── Detect, record, repair, verify ───────────────────────────────────────────
--
-- The whole recovery, in one callable unit, used by the scheduled job AND the admin action. There
-- is no path where a human has to press anything for the site to be accurate.
--
-- p_batch bounds the work: at most that many companies are repaired per call, so a large backlog is
-- cleared over several runs instead of one statement holding locks across the whole table.
--
-- Returns one row: what was found, what was fixed, and what still disagrees after repair.
CREATE OR REPLACE FUNCTION company_stats_maintain(p_batch INT DEFAULT 200)
RETURNS TABLE (drift_found INT, companies_repaired INT, still_drifted INT) AS $$
DECLARE
    v_ids   BIGINT[];
    v_found INT := 0;
    v_left  INT := 0;
BEGIN
    -- One snapshot of the discrepancies, recorded BEFORE anything is repaired. Evidence first: if
    -- the repair below fails or the process dies, what was wrong is already written down.
    CREATE TEMP TABLE IF NOT EXISTS _drift_now (
        company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT
    ) ON COMMIT DROP;
    DELETE FROM _drift_now;

    INSERT INTO _drift_now
    SELECT * FROM company_stats_detect_drift(p_batch * 8);

    SELECT count(*) INTO v_found FROM _drift_now;
    IF v_found = 0 THEN
        RETURN QUERY SELECT 0, 0, 0;
        RETURN;
    END IF;

    -- Bounded: the distinct companies to repair this pass.
    SELECT array_agg(DISTINCT company_id ORDER BY company_id) INTO v_ids
    FROM (SELECT DISTINCT company_id FROM _drift_now ORDER BY company_id LIMIT p_batch) AS b;

    INSERT INTO company_stats_drift_log (company_id, field, expected, actual, drift_kind)
    SELECT company_id, field, expected, actual, drift_kind
    FROM _drift_now
    WHERE company_id = ANY (v_ids);

    -- Repair through the shared implementation. No separate recount lives here.
    PERFORM company_stats_apply(v_ids);

    -- Verify: re-detect, restricted to what was just repaired. A repair that did not take must not
    -- be recorded as success.
    CREATE TEMP TABLE IF NOT EXISTS _drift_after (
        company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT
    ) ON COMMIT DROP;
    DELETE FROM _drift_after;

    INSERT INTO _drift_after
    SELECT * FROM company_stats_detect_drift(p_batch * 8) d WHERE d.company_id = ANY (v_ids);

    SELECT count(*) INTO v_left FROM _drift_after;

    UPDATE company_stats_drift_log l
       SET repair_result = CASE
               WHEN EXISTS (SELECT 1 FROM _drift_after a
                             WHERE a.company_id = l.company_id AND a.field = l.field)
               THEN 'unverified' ELSE 'repaired' END,
           repaired_at = now()
     WHERE l.repair_result = 'pending'
       AND l.company_id = ANY (v_ids);

    RETURN QUERY SELECT v_found, cardinality(v_ids), v_left;
END;
$$ LANGUAGE plpgsql;

-- ═════════════════════════════════════════════════════════════════════════════
-- 5. INDEXES FOR THE READ PATH
-- ═════════════════════════════════════════════════════════════════════════════
--
-- The projection stays a physical table (no view, no materialized view - CLAUDE.md section 21).
-- Every directory read filters manager_count > 0 and orders by total_reviews DESC, manager_count
-- DESC, so a partial index matching that predicate serves them and stays small: it indexes only
-- the companies that actually appear in the directory, not the one-row-per-company whole.
CREATE INDEX IF NOT EXISTS company_stats_live_directory_idx
    ON company_stats_live (total_reviews DESC, manager_count DESC)
    WHERE manager_count > 0;

-- ═════════════════════════════════════════════════════════════════════════════
-- 6. BACKFILL
-- ═════════════════════════════════════════════════════════════════════════════
--
-- Establishes the row-existence rule for the companies that predate it, and corrects anything the
-- previous writers left wrong. Through the shared function, so this cannot drift from it the way
-- V47's inline backfill drifted from V47's own trigger.
SELECT company_stats_apply(NULL);
