-- ─────────────────────────────────────────────────────────────────────────────
-- THE DATABASE CLASSIFIES A PLACEHOLDER, SO NO DEPLOYMENT CAN FAIL TO
--
-- V106 added `reviews.origin` with DEFAULT 'contributor' and backfilled every seed
-- that existed when it ran. Both halves were correct. Neither could cover a seed
-- written AFTERWARDS by an application build that does not name the column, and
-- that is what happened: on 2026-11-16 production held three rows with
-- `origin = 'contributor' AND weight = TRUE AND user_id IS NULL` - authors
-- SwiftFinch38, CoolFinch57 and BoldHeron70, the generated adjective-animal-number
-- shape `createSeedReview` alone produces.
--
-- They cannot predate V106's backfill, because its predicate is exactly the one
-- that selects them. They were inserted after it, by a backend built before the
-- classification literal landed in `createSeedReview` (commit 41c92ee, which also
-- carries V106 itself). Two backends write to this database and they deploy
-- separately, so "the code is fixed" is a statement about a repository, not about
-- what is running.
--
-- `created_at` cannot be used to date them. `createSeedReview` invents it as a
-- random 1 to 179 days in the past, so the newest row can carry the oldest
-- timestamp. That is why the three appeared to span four months while the admin
-- tile climbed from 1 to 3 inside a day.
--
-- WHY THIS IS A DATA-INTEGRITY DEFECT AND NOT A WRONG NUMBER. `ReviewSql.genuine`
-- is `live AND origin = 'contributor'`, and it is the predicate `recalculate()`
-- uses, so a misclassified placeholder reaches:
--
--   * the manager's cached overall_rating, reviews_count and category_averages;
--   * company_stats_live and the employment-attributed company and industry
--     statistics, which the V89/V90/V101 triggers derive from those;
--   * the public profile, where `isExample` reads `origin`, so an invented
--     3.5-4.9 score renders as an employee's opinion rather than a labelled
--     demonstration.
--
-- WHAT THIS MIGRATION DOES. It stops relying on every writer being up to date.
-- A placeholder is identifiable from the row itself - `weight = TRUE` with no
-- `user_id` - and that shape is written by `createSeedReview` and by nothing else,
-- the claim V106 made after grepping every INSERT and UPDATE touching `weight`
-- and which still holds today. So the database classifies it, whichever build
-- performed the insert and whether or not that build has heard of the column.
--
-- IT CLASSIFIES RATHER THAN REFUSING. A CHECK constraint or a raising trigger
-- would reject the insert, which would break ghost-manager creation outright on
-- any backend that has not been redeployed - a public search path, failing, to
-- protect a column the caller does not know about. Correcting the row keeps the
-- old build working and the data right.
--
-- Transactional, like every Flyway migration here. Reversible by dropping the
-- trigger; the backfill's undo is the operator rollback script beside it.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. the classification, in the database ──────────────────────────────────
CREATE OR REPLACE FUNCTION classify_placeholder_rating() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  /*
    Reached only through the trigger's WHEN clause, so by the time this runs the
    row is already known to be a placeholder carrying the column default. There is
    nothing left to decide.
  */
  NEW.origin := 'platform_example';
  RETURN NEW;
END $$;

COMMENT ON FUNCTION classify_placeholder_rating() IS
  'Marks a placeholder rating as a platform example at INSERT, so a backend built '
  'before reviews.origin existed cannot write an unclassified seed into the shared '
  'database. See V108.';

/*
  A WHEN clause, not an unconditional row trigger.

  Every genuine submission has weight = FALSE, so the condition is false and the
  function is never entered: the cost on the write path people actually use is the
  evaluation of three booleans in the executor, not a PL/pgSQL call.

  INSERT only. No path updates `weight` to TRUE on an existing row - the only
  UPDATE touching the column at all is startWeightWindow, which sets
  weight_expires_on - so an UPDATE trigger would guard a shape that cannot occur
  and would then have to reason about reclassification, which is a different and
  far more dangerous question.
*/
DROP TRIGGER IF EXISTS reviews_classify_placeholder ON reviews;
CREATE TRIGGER reviews_classify_placeholder
  BEFORE INSERT ON reviews
  FOR EACH ROW
  WHEN (NEW.weight AND NEW.user_id IS NULL AND NEW.origin = 'contributor')
  EXECUTE FUNCTION classify_placeholder_rating();

-- ── 2. a backup of exactly the rows the backfill touches ────────────────────
-- Before, not after. `origin` is the only column that changes, so this table plus
-- the recalculation in step 4 is the whole undo. The operator rollback script is
-- db/preflight/rollback_v108_seed_classification.sql.
--
-- Shared with the hand-run reclassification script on purpose: whichever ran
-- first, the other finds its rows already recorded and adds only what is new.
CREATE TABLE IF NOT EXISTS reviews_seed_reclassify_backup (
  id           UUID PRIMARY KEY,
  manager_id   BIGINT      NOT NULL,
  origin       TEXT        NOT NULL,
  author       TEXT,
  created_at   TIMESTAMPTZ,
  backed_up_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO reviews_seed_reclassify_backup (id, manager_id, origin, author, created_at)
SELECT r.id, r.manager_id, r.origin, r.author, r.created_at
  FROM reviews r
 WHERE r.origin  = 'contributor'
   AND r.weight  = TRUE
   AND r.user_id IS NULL
ON CONFLICT (id) DO NOTHING;

-- Which rows THIS migration is accountable for, as against rows a previous run of
-- the hand-run script already corrected and recorded in the same backup table.
CREATE TEMP TABLE v108_targets ON COMMIT DROP AS
SELECT r.id, r.manager_id
  FROM reviews r
 WHERE r.origin  = 'contributor'
   AND r.weight  = TRUE
   AND r.user_id IS NULL;

-- ── 3. the backfill ────────────────────────────────────────────────────────
-- V106's predicate, unchanged. It is restricted to rows that are STILL seeds by
-- that test, so it cannot reclassify a contributor's rating.
--
-- NOT touched, by design: a row with weight = TRUE and a user_id. No write path
-- produces that shape, so each one is a question rather than a seed, and calling a
-- real employee's rating a demonstration is far worse than leaving a seed
-- uncounted. Step 5 reports them.
--
-- This UPDATE fires the V101 statement triggers on `reviews`, so the
-- employment-attributed company and industry statistics follow inside this
-- transaction.
UPDATE reviews
   SET origin = 'platform_example'
 WHERE origin  = 'contributor'
   AND weight  = TRUE
   AND user_id IS NULL;

-- ── 4. the manager cache, recomputed the way recalculate() computes it ─────
-- An example contributes nothing, so every manager the backfill touched is
-- carrying a cached average and count that included a demonstration. Nothing
-- recomputes that on its own: recalculate() runs on a rating write, and no rating
-- was written here.
--
-- Mirrors ManagerRepository.recalculate exactly, which matters in three places
-- that are easy to get wrong:
--   * the predicate is ReviewSql.genuine, which is
--     `disposition = 'live' AND deleted_at IS NULL AND origin = 'contributor'`;
--   * at zero genuine ratings, overall_rating and category_averages go NULL, not
--     0 and not an object of zeroes;
--   * a NULL category average inside a nonzero count becomes 0.0, matching
--     nullSafe().
--
-- The UPDATE on `managers` fires the V89 statement trigger, so company_stats_live,
-- company and industry statistics and rankings follow inside this transaction.
WITH affected AS (
  SELECT DISTINCT manager_id FROM v108_targets
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

-- ── 4b. the dependent statistics, recomputed and then CHECKED ──────────────
-- The triggers above should already have done this: the `managers` UPDATE fires
-- V89's statement trigger, and the `reviews` UPDATE fires V101's. This section
-- does not trust that. It recomputes both projections for every affected company
-- from source data and then asserts the recomputation changed nothing - so the
-- migration proves the propagation happened rather than assuming it.
--
-- Cheap: this runs over the handful of companies whose managers held a
-- misclassified placeholder, not the table.
--
-- There is no separate industry projection to repair. `companies.industry` is a
-- column (V46) and industry figures are aggregated from company stats at query
-- time, so correcting company stats corrects them by construction.
CREATE TEMP TABLE v108_stats_before ON COMMIT DROP AS
SELECT s.company_id, s.manager_count, s.total_reviews, s.avg_rating
  FROM company_stats_live s
 WHERE s.company_id IN (
     SELECT DISTINCT m.company_id FROM managers m
      JOIN v108_targets t ON t.manager_id = m.id
     WHERE m.company_id IS NOT NULL);

DO $$
DECLARE
  r       RECORD;
  v_ids   BIGINT[];
  v_drift BIGINT;
BEGIN
  SELECT array_agg(DISTINCT m.company_id) INTO v_ids
    FROM managers m JOIN v108_targets t ON t.manager_id = m.id
   WHERE m.company_id IS NOT NULL;

  IF v_ids IS NULL THEN
    RAISE NOTICE 'V108: no affected company, so no statistics to recompute';
    RETURN;
  END IF;

  FOR r IN SELECT unnest(v_ids) AS company_id LOOP
    PERFORM refresh_company_stats(r.company_id);
  END LOOP;
  PERFORM company_employment_stats_apply(v_ids);

  SELECT count(*) INTO v_drift
    FROM v108_stats_before b
    JOIN company_stats_live s ON s.company_id = b.company_id
   WHERE b.manager_count IS DISTINCT FROM s.manager_count
      OR b.total_reviews IS DISTINCT FROM s.total_reviews
      OR b.avg_rating    IS DISTINCT FROM s.avg_rating;

  IF v_drift > 0 THEN
    RAISE NOTICE 'V108: % company stat row(s) were STALE and have been corrected. The triggers '
                 'that should have followed the reclassification did not, which is worth '
                 'investigating separately - the figures are right now either way.', v_drift;
  ELSE
    RAISE NOTICE 'V108: company statistics for % company(ies) verified against source data',
                 array_length(v_ids, 1);
  END IF;
END $$;

-- ── 5. the migration accounts for itself ───────────────────────────────────
DO $$
DECLARE
  v_fixed     BIGINT;
  v_remaining BIGINT;
  v_ambiguous BIGINT;
BEGIN
  SELECT count(*) INTO v_fixed     FROM v108_targets;
  SELECT count(*) INTO v_remaining FROM reviews
   WHERE origin = 'contributor' AND weight = TRUE AND user_id IS NULL;
  SELECT count(*) INTO v_ambiguous FROM reviews
   WHERE weight = TRUE AND user_id IS NOT NULL;

  RAISE NOTICE 'V108: % unclassified placeholder(s) reclassified; % manager cache(s) recomputed',
               v_fixed, (SELECT count(DISTINCT manager_id) FROM v108_targets);

  IF v_remaining > 0 THEN
    RAISE EXCEPTION 'V108 refuses: % unclassified placeholders remain after the backfill',
                    v_remaining;
  END IF;

  IF v_ambiguous > 0 THEN
    RAISE NOTICE 'V108: % row(s) carry weight = TRUE WITH a user_id. NOT touched, by design: no '
                 'write path produces that shape, so each is a question rather than a seed. '
                 'They keep origin = contributor, which is the safe direction.', v_ambiguous;
  END IF;
END $$;

-- ── 6. where the trigger is proved to fire ─────────────────────────────────
-- Not here. A self-test inside this migration would have to insert a reviews row
-- that satisfies every other constraint on the table, so it could fail the
-- migration for a reason having nothing to do with the trigger - the worst
-- possible trade on a production deployment.
--
-- The proof lives in ReviewIntegrationTest
-- (`seedWrittenWithoutItsOriginIsClassifiedByTheDatabase`), which runs against a
-- database migrated from V1 and inserts a placeholder exactly the way a pre-V106
-- build does: naming no origin at all. It asserts the stored row reads
-- platform_example, and it is written to fail if this trigger or its WHEN clause
-- is ever dropped.
