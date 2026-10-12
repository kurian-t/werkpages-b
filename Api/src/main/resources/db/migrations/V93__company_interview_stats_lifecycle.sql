-- ═════════════════════════════════════════════════════════════════════════════
-- company_interview_stats: the same lifecycle company_stats_live already has
-- ═════════════════════════════════════════════════════════════════════════════
--
-- V48 gave this projection triggers, which satisfies rules 1 to 3 of CLAUDE.md section 21: the
-- source tables stay authoritative, every mutation updates the projection in the same transaction,
-- and maintenance lives in one place. It never got rules 4 and 5 - a rebuild utility, and a
-- reconciliation routine that compares the projection against its sources and reports drift.
--
-- Section 21 is explicit about what that means: "A read table without (4) and (5) is not a cache;
-- it is a second source of truth that will silently diverge." This projection is read by
-- getCompanyInterviews and rendered on the company page, so a divergence here is wrong numbers on
-- a page people read, with nothing anywhere saying so.
--
-- V90 built all of this for company_stats_live. This migration does not invent a second approach;
-- it applies the same one, reusing V90's drift log and its scheduled maintenance so that one job
-- and one alert cover both projections.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- THE RACE THIS ALSO FIXES
-- ─────────────────────────────────────────────────────────────────────────────
--
-- V48's trigger recomputes from `interview_reviews` with no lock. Two transactions inserting
-- reviews for the same company therefore read, under READ COMMITTED, two snapshots that each
-- exclude the other's uncommitted row:
--
--   T1: insert review A -> trigger counts {A}            = 1
--   T2: insert review B -> trigger counts {B}            = 1
--   T1 commits (row says 1), T2 commits (row says 1)
--   truth: 2
--
-- The loser's write lands last and the count is permanently short. This is the same race V90
-- documented for company_stats_live, and the fix is the same: take the per-company advisory lock
-- BEFORE reading the source, so the second transaction waits and then sees the first one's row.
--
-- The lock uses V90's key, 847231, deliberately - NOT a key of its own. A write to
-- `interview_reviews` fires this projection's trigger and company_stats_live's, so two different
-- keys would mean two locks taken per transaction and an ordering question to get wrong. One lock
-- per company covers all projection maintenance for that company, and advisory locks are
-- re-entrant within a transaction, so the nested acquisition is free.

-- ═════════════════════════════════════════════════════════════════════════════
-- 1. WHICH PROJECTION A DRIFT RECORD BELONGS TO
-- ═════════════════════════════════════════════════════════════════════════════
--
-- The log gains a column rather than the schema gaining a second log. The questions asked of it -
-- what drifted, was it repaired, has this company drifted before - are identical for both
-- projections, and splitting the table would mean asking each of them twice and remembering to.
--
-- Defaulted, so every existing row keeps its meaning: before this migration the log could only
-- ever have described company_stats_live.
ALTER TABLE company_stats_drift_log
    ADD COLUMN IF NOT EXISTS projection TEXT NOT NULL DEFAULT 'company_stats_live';

CREATE INDEX IF NOT EXISTS company_stats_drift_log_projection_idx
    ON company_stats_drift_log (projection, detected_at DESC);

-- ═════════════════════════════════════════════════════════════════════════════
-- 2. WHAT THE PROJECTION SHOULD CONTAIN
-- ═════════════════════════════════════════════════════════════════════════════
--
-- The definition, in one place, as a function of the source tables alone. This is what makes the
-- rebuild and the drift check agree by construction: both read this, so neither can hold a
-- different idea of the truth.
--
-- It returns a row ONLY for a company that has at least one live interview review, which is the
-- existence rule V48 established with its `DELETE ... WHERE review_count = 0`. A company whose
-- last review is soft-deleted has no row, not a row of zeroes - so the drift check can tell
-- "should have no row" from "should have a row saying zero".
CREATE OR REPLACE FUNCTION company_interview_stats_expected(p_company_ids BIGINT[] DEFAULT NULL)
RETURNS TABLE (
    company_id     BIGINT,
    review_count   INTEGER,
    avg_rating     NUMERIC(2,1),
    avg_difficulty NUMERIC(2,1),
    offer_count    INTEGER,
    offer_avg      NUMERIC(2,1),
    no_offer_count INTEGER,
    no_offer_avg   NUMERIC(2,1),
    median_rounds  SMALLINT
) AS $$
    SELECT ir.company_id,
           COUNT(*)::INTEGER,
           ROUND(AVG(ir.overall_rating)::NUMERIC, 1)::NUMERIC(2,1),
           ROUND(AVG(ir.difficulty)::NUMERIC, 1)::NUMERIC(2,1),
           COUNT(*) FILTER (WHERE ir.outcome = 'offer')::INTEGER,
           ROUND(AVG(ir.overall_rating) FILTER (WHERE ir.outcome = 'offer')::NUMERIC, 1)::NUMERIC(2,1),
           COUNT(*) FILTER (WHERE ir.outcome = 'no_offer')::INTEGER,
           ROUND(AVG(ir.overall_rating) FILTER (WHERE ir.outcome = 'no_offer')::NUMERIC, 1)::NUMERIC(2,1),
           PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY ir.rounds)::SMALLINT
    FROM interview_reviews ir
    -- A review whose company has been deleted or merged away projects nothing. The join is what
    -- enforces that, and it is why a merged company's stats row is reported as obsolete rather
    -- than quietly recomputed under an id nothing points at any more.
    JOIN companies c ON c.id = ir.company_id AND c.status <> 'merged'
    WHERE ir.deleted_at IS NULL
      AND (p_company_ids IS NULL OR ir.company_id = ANY (p_company_ids))
    GROUP BY ir.company_id;
$$ LANGUAGE sql STABLE;

-- ═════════════════════════════════════════════════════════════════════════════
-- 3. THE ONE MAINTENANCE ENTRY POINT
-- ═════════════════════════════════════════════════════════════════════════════
--
-- Recomputes the given companies, or every company when passed NULL - which is the full rebuild
-- required by section 21 rule 4. There is no second rebuild implementation: the trigger, the
-- scheduled repair and the rebuild all come through here.
CREATE OR REPLACE FUNCTION company_interview_stats_apply(p_company_ids BIGINT[] DEFAULT NULL)
RETURNS BIGINT AS $$
DECLARE
    v_ids     BIGINT[];
    v_id      BIGINT;
    v_written BIGINT := 0;
BEGIN
    IF p_company_ids IS NULL THEN
        -- Every company that has a row now, plus every company that should have one. Both halves
        -- are needed: the first finds rows to delete, the second finds rows to create, and a
        -- rebuild that only looked at one would leave the other kind of error in place.
        SELECT array_agg(DISTINCT x ORDER BY x) INTO v_ids
        FROM (
            SELECT company_id AS x FROM company_interview_stats
            UNION
            SELECT DISTINCT company_id FROM interview_reviews WHERE deleted_at IS NULL
        ) AS all_ids;
    ELSE
        -- Deduplicated and sorted, as in company_stats_apply: dedup stops a bulk statement
        -- recomputing one company once per affected row, and the ascending order gives every
        -- caller the same lock sequence.
        SELECT array_agg(DISTINCT x ORDER BY x) INTO v_ids
        FROM unnest(p_company_ids) AS x
        WHERE x IS NOT NULL;
    END IF;

    IF v_ids IS NULL OR cardinality(v_ids) = 0 THEN
        RETURN 0;
    END IF;

    FOREACH v_id IN ARRAY v_ids LOOP
        -- BEFORE the source read. See the race described at the top of this file.
        PERFORM pg_advisory_xact_lock(847231, (v_id % 2147483647)::INT);

        -- No company, or a merged one: no projection row. Only the projection is touched here;
        -- maintenance never deletes a source review.
        IF NOT EXISTS (SELECT 1 FROM companies WHERE id = v_id AND status <> 'merged') THEN
            DELETE FROM company_interview_stats WHERE company_id = v_id;
            v_written := v_written + 1;
            CONTINUE;
        END IF;

        INSERT INTO company_interview_stats AS cis
               (company_id, review_count, avg_rating, avg_difficulty,
                offer_count, offer_avg, no_offer_count, no_offer_avg, median_rounds, updated_at)
        SELECT e.company_id, e.review_count, e.avg_rating, e.avg_difficulty,
               e.offer_count, e.offer_avg, e.no_offer_count, e.no_offer_avg, e.median_rounds, now()
        FROM company_interview_stats_expected(ARRAY[v_id]) e
        ON CONFLICT (company_id) DO UPDATE SET
            review_count   = EXCLUDED.review_count,
            avg_rating     = EXCLUDED.avg_rating,
            avg_difficulty = EXCLUDED.avg_difficulty,
            offer_count    = EXCLUDED.offer_count,
            offer_avg      = EXCLUDED.offer_avg,
            no_offer_count = EXCLUDED.no_offer_count,
            no_offer_avg   = EXCLUDED.no_offer_avg,
            median_rounds  = EXCLUDED.median_rounds,
            updated_at     = now();

        -- The expected set returns nothing for a company with no live reviews, so the upsert above
        -- wrote nothing and any existing row is now stale. Deleting it here is what keeps the
        -- existence rule true in both directions.
        DELETE FROM company_interview_stats
        WHERE company_id = v_id
          AND NOT EXISTS (SELECT 1 FROM company_interview_stats_expected(ARRAY[v_id]));

        v_written := v_written + 1;
    END LOOP;

    RETURN v_written;
END;
$$ LANGUAGE plpgsql;

-- V48's name, kept so the trigger and anything else already calling it does not change, and so
-- there is still only one implementation underneath. The guard clauses it used to carry now live
-- in company_interview_stats_apply, which is the only thing that recomputes.
CREATE OR REPLACE FUNCTION refresh_company_interview_stats(p_company_id BIGINT) RETURNS void AS $$
BEGIN
    IF p_company_id IS NOT NULL THEN
        PERFORM company_interview_stats_apply(ARRAY[p_company_id]);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ═════════════════════════════════════════════════════════════════════════════
-- 4. DETECTION
-- ═════════════════════════════════════════════════════════════════════════════
--
-- Expected against stored, for every company, in ONE statement - so every comparison sees the same
-- snapshot. Comparing per company in a loop would let a review committed halfway through the run
-- look like drift that never existed.
--
-- A FULL OUTER JOIN, because three different things can be wrong:
--   * both sides present, numbers differ   -> 'value'
--   * expected present, no stored row      -> 'missing_row'
--   * stored row, nothing expected         -> 'obsolete_row'
--
-- Counts and the median only, and for the same reason V90 gives: an average that disagrees always
-- has a count that disagrees behind it, and comparing rounded numerics invites a false alarm over
-- the last decimal place. A checker that cries wolf gets switched off. The median IS compared -
-- it is a discrete value, not a rounded mean, so it cannot disagree by a rounding artefact.
CREATE OR REPLACE FUNCTION company_interview_stats_detect_drift(p_limit INT DEFAULT 1000)
RETURNS TABLE (company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT) AS $$
    WITH expected AS (
        SELECT * FROM company_interview_stats_expected(NULL)
    ),
    compared AS (
        SELECT COALESCE(e.company_id, cis.company_id) AS cid,
               e.company_id   IS NULL AS obsolete,
               cis.company_id IS NULL AS missing,
               e.review_count,   cis.review_count   AS a_review_count,
               e.offer_count,    cis.offer_count    AS a_offer_count,
               e.no_offer_count, cis.no_offer_count AS a_no_offer_count,
               e.median_rounds,  cis.median_rounds  AS a_median_rounds
        FROM expected e
        FULL OUTER JOIN company_interview_stats cis ON cis.company_id = e.company_id
    )
    SELECT cid, field, expected, actual, kind FROM (
        SELECT cid, 'row' AS field, 'present' AS expected, 'absent' AS actual,
               'missing_row' AS kind, 0 AS ord
        FROM compared WHERE missing
        UNION ALL
        SELECT cid, 'row', 'absent', 'present', 'obsolete_row', 1
        FROM compared WHERE obsolete
        UNION ALL
        SELECT cid, 'review_count', review_count::TEXT, a_review_count::TEXT, 'value', 2
        FROM compared WHERE NOT missing AND NOT obsolete AND review_count <> a_review_count
        UNION ALL
        SELECT cid, 'offer_count', offer_count::TEXT, a_offer_count::TEXT, 'value', 3
        FROM compared WHERE NOT missing AND NOT obsolete AND offer_count <> a_offer_count
        UNION ALL
        SELECT cid, 'no_offer_count', no_offer_count::TEXT, a_no_offer_count::TEXT, 'value', 4
        FROM compared WHERE NOT missing AND NOT obsolete AND no_offer_count <> a_no_offer_count
        UNION ALL
        SELECT cid, 'median_rounds', median_rounds::TEXT, a_median_rounds::TEXT, 'value', 5
        FROM compared
        WHERE NOT missing AND NOT obsolete AND median_rounds IS DISTINCT FROM a_median_rounds
    ) AS drift
    ORDER BY cid, ord
    LIMIT p_limit;
$$ LANGUAGE sql STABLE;

-- ═════════════════════════════════════════════════════════════════════════════
-- 5. DETECT, RECORD, REPAIR, VERIFY
-- ═════════════════════════════════════════════════════════════════════════════
--
-- The interview projection's half of the recovery, same four steps and same guarantees as V90's.
CREATE OR REPLACE FUNCTION company_interview_stats_maintain(p_batch INT DEFAULT 200)
RETURNS TABLE (drift_found INT, companies_repaired INT, still_drifted INT) AS $$
DECLARE
    v_ids   BIGINT[];
    v_found INT := 0;
    v_left  INT := 0;
BEGIN
    CREATE TEMP TABLE IF NOT EXISTS _iv_drift_now (
        company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT
    ) ON COMMIT DROP;
    DELETE FROM _iv_drift_now;

    -- Evidence first: recorded before anything is repaired, so a repair that fails or a process
    -- that dies still leaves what was wrong written down.
    INSERT INTO _iv_drift_now
    SELECT * FROM company_interview_stats_detect_drift(p_batch * 8);

    SELECT count(*) INTO v_found FROM _iv_drift_now;
    IF v_found = 0 THEN
        RETURN QUERY SELECT 0, 0, 0;
        RETURN;
    END IF;

    SELECT array_agg(DISTINCT company_id ORDER BY company_id) INTO v_ids
    FROM (SELECT DISTINCT company_id FROM _iv_drift_now ORDER BY company_id LIMIT p_batch) AS b;

    INSERT INTO company_stats_drift_log
           (company_id, field, expected, actual, drift_kind, projection)
    SELECT company_id, field, expected, actual, drift_kind, 'company_interview_stats'
    FROM _iv_drift_now
    WHERE company_id = ANY (v_ids);

    -- Repair through the shared implementation. No separate recount lives here.
    PERFORM company_interview_stats_apply(v_ids);

    CREATE TEMP TABLE IF NOT EXISTS _iv_drift_after (
        company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT
    ) ON COMMIT DROP;
    DELETE FROM _iv_drift_after;

    -- Verify, restricted to what was just repaired. A repair that did not take must not be
    -- recorded as a success.
    INSERT INTO _iv_drift_after
    SELECT * FROM company_interview_stats_detect_drift(p_batch * 8) d
    WHERE d.company_id = ANY (v_ids);

    SELECT count(*) INTO v_left FROM _iv_drift_after;

    UPDATE company_stats_drift_log l
       SET repair_result = CASE
               WHEN EXISTS (SELECT 1 FROM _iv_drift_after a
                             WHERE a.company_id = l.company_id AND a.field = l.field)
               THEN 'unverified' ELSE 'repaired' END,
           repaired_at = now()
     WHERE l.repair_result = 'pending'
       AND l.projection = 'company_interview_stats'
       AND l.company_id = ANY (v_ids);

    RETURN QUERY SELECT v_found, cardinality(v_ids), v_left;
END;
$$ LANGUAGE plpgsql;

-- ═════════════════════════════════════════════════════════════════════════════
-- 6. ONE SCHEDULED JOB COVERS BOTH PROJECTIONS
-- ═════════════════════════════════════════════════════════════════════════════
--
-- company_stats_maintain keeps its name, its signature and its meaning - "repair the company
-- statistics" - and now covers both projections, with the totals summed.
--
-- Extending it rather than adding a second entry point is the point of doing it this way. The
-- scheduled job, the staleness check, the alerting and the admin recovery action all call this one
-- function; a separate company_interview_stats_maintain left for somebody to wire up would be a
-- projection with a repair routine nothing calls, which is indistinguishable from no repair
-- routine at all. The per-projection functions remain callable for diagnosis, and the drift log's
-- `projection` column says which one a finding came from.
CREATE OR REPLACE FUNCTION company_stats_maintain(p_batch INT DEFAULT 200)
RETURNS TABLE (drift_found INT, companies_repaired INT, still_drifted INT) AS $$
DECLARE
    v_ids    BIGINT[];
    v_found  INT := 0;
    v_left   INT := 0;
    v_fixed  INT := 0;
    v_iv     RECORD;
BEGIN
    -- ── company_stats_live ───────────────────────────────────────────────────
    CREATE TEMP TABLE IF NOT EXISTS _drift_now (
        company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT
    ) ON COMMIT DROP;
    DELETE FROM _drift_now;

    INSERT INTO _drift_now
    SELECT * FROM company_stats_detect_drift(p_batch * 8);

    SELECT count(*) INTO v_found FROM _drift_now;

    IF v_found > 0 THEN
        SELECT array_agg(DISTINCT company_id ORDER BY company_id) INTO v_ids
        FROM (SELECT DISTINCT company_id FROM _drift_now ORDER BY company_id LIMIT p_batch) AS b;

        INSERT INTO company_stats_drift_log
               (company_id, field, expected, actual, drift_kind, projection)
        SELECT company_id, field, expected, actual, drift_kind, 'company_stats_live'
        FROM _drift_now
        WHERE company_id = ANY (v_ids);

        PERFORM company_stats_apply(v_ids);

        CREATE TEMP TABLE IF NOT EXISTS _drift_after (
            company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT
        ) ON COMMIT DROP;
        DELETE FROM _drift_after;

        INSERT INTO _drift_after
        SELECT * FROM company_stats_detect_drift(p_batch * 8) d WHERE d.company_id = ANY (v_ids);

        SELECT count(*) INTO v_left FROM _drift_after;
        v_fixed := cardinality(v_ids);

        UPDATE company_stats_drift_log l
           SET repair_result = CASE
                   WHEN EXISTS (SELECT 1 FROM _drift_after a
                                 WHERE a.company_id = l.company_id AND a.field = l.field)
                   THEN 'unverified' ELSE 'repaired' END,
               repaired_at = now()
         WHERE l.repair_result = 'pending'
           AND l.projection = 'company_stats_live'
           AND l.company_id = ANY (v_ids);
    END IF;

    -- ── company_interview_stats ──────────────────────────────────────────────
    --
    -- Added to the same totals. The caller's question is "is the site's statistics accurate", and
    -- one number answering it for one projection while another quietly disagreed is how a
    -- projection goes unwatched.
    SELECT * INTO v_iv FROM company_interview_stats_maintain(p_batch);

    RETURN QUERY SELECT v_found + v_iv.drift_found,
                        v_fixed + v_iv.companies_repaired,
                        v_left  + v_iv.still_drifted;
END;
$$ LANGUAGE plpgsql;

-- ═════════════════════════════════════════════════════════════════════════════
-- 7. BACKFILL
-- ═════════════════════════════════════════════════════════════════════════════
--
-- Corrects whatever the unlocked V48 trigger lost before this migration existed. Cheap: it touches
-- only companies that have, or should have, an interview stats row.
SELECT company_interview_stats_apply(NULL);
