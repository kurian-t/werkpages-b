-- ─────────────────────────────────────────────────────────────────────────────
-- ROLLBACK: PUT THE RECLASSIFIED PLACEHOLDERS BACK AS THEY WERE
--
-- Undoes V108 section 3 and prod_reclassify_unclassified_seeds.sql - the same
-- operation, whichever applied it, because both write the same backup table.
--
-- WHAT IT UNDOES. Those steps changed exactly one column on exactly the rows
-- recorded in reviews_seed_reclassify_backup: origin, from 'contributor' to
-- 'platform_example'. So restoring that column and recomputing the manager cache
-- is the complete undo, and the dependent statistics follow from the triggers the
-- same way they followed forward.
--
-- WHAT IT DOES NOT UNDO, and you almost certainly do not want it to. The V108
-- trigger is still installed, so the next placeholder written by an out-of-date
-- backend is still classified. Rolling that back is one statement, and it should
-- be a deliberate decision rather than a side effect of restoring three rows:
--
--   DROP TRIGGER reviews_classify_placeholder ON reviews;
--
-- Doing so does not remove the FUNCTION, so re-installing it is the CREATE
-- TRIGGER from V108 alone.
--
-- WHEN YOU WOULD RUN THIS. If a row turns out to have been a genuine rating that
-- somebody had made anonymous and that carried weight for an unrelated reason -
-- the shape V106 and V108 both deliberately refuse to classify when a user_id is
-- present, but which could in principle exist without one. Restoring is then
-- correct and this is how.
--
-- Transactional. Idempotent: on already-restored data it reports and changes
-- nothing. Verified by running it against a database that had V108 applied, and
-- comparing managers.reviews_count and overall_rating before and after.
-- ─────────────────────────────────────────────────────────────────────────────

\set ON_ERROR_STOP on
BEGIN;

-- ── 0. there has to be something to restore FROM ────────────────────────────
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                  WHERE table_name = 'reviews_seed_reclassify_backup') THEN
    RAISE EXCEPTION 'reviews_seed_reclassify_backup does not exist, so no reclassification '
                    'has ever been recorded and there is nothing to roll back';
  END IF;
END $$;

\echo '=============================================================='
\echo 'ROWS RECORDED AS RECLASSIFIED, AND WHAT THEY READ NOW'
\echo '=============================================================='
SELECT b.id,
       b.manager_id,
       m.name        AS manager_name,
       b.author,
       b.origin      AS origin_before,
       r.origin      AS origin_now,
       b.backed_up_at
  FROM reviews_seed_reclassify_backup b
  LEFT JOIN reviews  r ON r.id = b.id
  LEFT JOIN managers m ON m.id = b.manager_id
 ORDER BY b.backed_up_at, b.manager_id;

\echo '--- the manager cache as it stands, BEFORE the restore ---'
SELECT m.id, m.name, m.reviews_count, m.overall_rating
  FROM managers m
 WHERE m.id IN (SELECT manager_id FROM reviews_seed_reclassify_backup)
 ORDER BY m.id;

-- ── 1. the rows this run actually changes ───────────────────────────────────
CREATE TEMP TABLE this_run ON COMMIT DROP AS
SELECT b.id, b.manager_id, b.origin
  FROM reviews_seed_reclassify_backup b
  JOIN reviews r ON r.id = b.id
 WHERE r.origin <> b.origin;

-- ── 2. the restore ──────────────────────────────────────────────────────────
-- Keyed on the backed-up id, so it can only ever touch rows that were recorded.
-- Fires the V101 statement triggers on `reviews`, so the employment-attributed
-- company and industry statistics follow inside this transaction.
UPDATE reviews r
   SET origin = t.origin
  FROM this_run t
 WHERE r.id = t.id;

-- ── 3. the manager cache, recomputed the way recalculate() computes it ─────
-- Identical to V108 section 4 and to the forward script's step 4, because the
-- undo faces exactly the same problem: nothing recomputes a cached average on its
-- own, and these rows are now genuine again by the predicate that counts.
--
-- The UPDATE on `managers` fires the V89 statement trigger, so company_stats_live,
-- company and industry statistics and rankings follow in this transaction.
WITH affected AS (
  SELECT DISTINCT manager_id FROM reviews_seed_reclassify_backup
),
genuine AS (
  SELECT a.manager_id,
         count(r.id)::INTEGER                                          AS n,
         round(avg(r.overall_rating)::NUMERIC, 1)                       AS overall,
         round(avg(r.communication_style)::NUMERIC, 1)                  AS c01,
         round(avg(r.perceived_approachability)::NUMERIC, 1)            AS c02,
         round(avg(r.perceived_clarity_of_expectations)::NUMERIC, 1)    AS c03,
         round(avg(r.feedback_style)::NUMERIC, 1)                       AS c04,
         round(avg(r.perceived_supportiveness)::NUMERIC, 1)             AS c05,
         round(avg(r.decision_making_style)::NUMERIC, 1)                AS c06,
         round(avg(r.organization_and_planning_style)::NUMERIC, 1)      AS c07,
         round(avg(r.delegation_style)::NUMERIC, 1)                     AS c08,
         round(avg(r.perceived_professional_demeanor)::NUMERIC, 1)      AS c09,
         round(avg(r.overall_working_experience)::NUMERIC, 1)           AS c10
    FROM affected a
    LEFT JOIN reviews r
           ON r.manager_id  = a.manager_id
          AND r.disposition = 'live'
          AND r.deleted_at IS NULL
          AND r.origin      = 'contributor'
   GROUP BY a.manager_id
)
UPDATE managers m
   SET reviews_count  = g.n,
       overall_rating = CASE WHEN g.n > 0 THEN g.overall END,
       category_averages = CASE WHEN g.n > 0 THEN jsonb_build_object(
           'Communication Style',               coalesce(g.c01, 0.0),
           'Perceived Approachability',         coalesce(g.c02, 0.0),
           'Perceived Clarity of Expectations', coalesce(g.c03, 0.0),
           'Feedback Style',                    coalesce(g.c04, 0.0),
           'Perceived Supportiveness',          coalesce(g.c05, 0.0),
           'Decision Making Style',             coalesce(g.c06, 0.0),
           'Organization and Planning Style',   coalesce(g.c07, 0.0),
           'Delegation Style',                  coalesce(g.c08, 0.0),
           'Perceived Professional Demeanor',   coalesce(g.c09, 0.0),
           'Overall Working Experience',        coalesce(g.c10, 0.0)
       ) END,
       updated_at = now()
  FROM genuine g
 WHERE m.id = g.manager_id;

\echo '--- the manager cache AFTER the restore ---'
SELECT m.id, m.name, m.reviews_count, m.overall_rating
  FROM managers m
 WHERE m.id IN (SELECT manager_id FROM reviews_seed_reclassify_backup)
 ORDER BY m.id;

-- ── 4. what this run did, and whether it took ───────────────────────────────
DO $$
DECLARE
  v_restored  BIGINT;
  v_mismatch  BIGINT;
  v_trigger   BOOLEAN;
BEGIN
  SELECT count(*) INTO v_restored FROM this_run;

  SELECT count(*) INTO v_mismatch
    FROM reviews_seed_reclassify_backup b
    JOIN reviews r ON r.id = b.id
   WHERE r.origin <> b.origin;

  SELECT EXISTS (SELECT 1 FROM pg_trigger
                  WHERE tgname = 'reviews_classify_placeholder' AND NOT tgisinternal)
    INTO v_trigger;

  IF v_restored = 0 THEN
    RAISE NOTICE 'Nothing to restore: every recorded row already carries its original origin.';
  ELSE
    RAISE NOTICE '% row(s) restored to their original origin', v_restored;
  END IF;

  IF v_mismatch > 0 THEN
    RAISE EXCEPTION 'restore did not take: % recorded row(s) still differ from the backup',
                    v_mismatch;
  END IF;

  IF v_trigger THEN
    RAISE NOTICE 'reviews_classify_placeholder is STILL INSTALLED, which is almost certainly '
                 'what you want: the next placeholder written by an out-of-date backend is '
                 'still classified. A row restored here that is genuinely a placeholder will '
                 'NOT be reclassified by it, because the trigger fires on INSERT only.';
  ELSE
    RAISE NOTICE 'reviews_classify_placeholder is NOT installed. An out-of-date backend can '
                 'write an unclassified placeholder again.';
  END IF;
END $$;

COMMIT;
