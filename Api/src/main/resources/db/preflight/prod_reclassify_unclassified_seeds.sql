-- ─────────────────────────────────────────────────────────────────────────────
-- POST-V107: RECLASSIFY A SEED THAT WAS WRITTEN WITHOUT ITS origin
--
-- WHAT WENT WRONG. V106 added `reviews.origin` with DEFAULT 'contributor' and
-- backfilled every seed that existed when it ran to 'platform_example'. The
-- backfill was complete for that instant. It cannot cover a seed written
-- AFTERWARDS by a backend that does not name the column, and one such backend is
-- still live: the RateMyManagers API, deployed before the redesign, whose
-- createSeedReview omits `origin` and therefore takes the default.
--
-- The result is a row that IS a platform demonstration and is CLASSIFIED as a
-- person's rating. That is not a cosmetic mislabel. ReviewSql.genuine() is
-- `live AND origin = 'contributor'`, and it is the predicate recalculate() uses,
-- so such a row reaches:
--
--   * the manager's cached overall_rating, reviews_count and category_averages;
--   * company_stats_live, company and industry statistics and rankings, which
--     V89/V90 derive from that manager cache;
--   * the admin realReviews and weightedOpinions tiles;
--   * the public profile, where isExample reads `origin` and so renders a
--     fabricated 3.5-4.9 score as an employee opinion rather than a labelled
--     demonstration.
--
-- HOW THE TILE FOUND IT. Post-V106 `origin = 'contributor' AND weight = TRUE`
-- is by design the empty set: weight is written by createSeedReview alone, and
-- every seed is an example. So the Weighted Opinions tile is now a drift
-- detector, and a nonzero reading means precisely this.
--
-- WHAT THIS DOES NOT DO. It does not touch a row carrying weight = TRUE WITH a
-- user_id. No write path produces that shape, so such a row is a question and
-- not a seed, and V106 refused to guess at it for the same reason: calling a
-- real employee's rating a demonstration is far worse than leaving a seed
-- uncounted. Those rows are REPORTED here and left exactly as they are.
--
-- SET-BASED, NOT ROW-BASED. The predicate below names no id and no author: it is
-- V106's backfill predicate, so it corrects every unclassified placeholder in the
-- table and not the one that happened to be noticed first. It was written that
-- way because the cause produces more of them over time, which is exactly what
-- happened - the count went from one on 2026-11-15 to three on 2026-11-16, and
-- `created_at` could not show it, being invented by createSeedReview as a random
-- 1 to 179 days in the past.
--
-- V108 NOW DOES THE SAME THING, AND PREVENTS IT RECURRING. It carries this
-- backfill and installs a BEFORE INSERT trigger that classifies a placeholder
-- whichever build inserted it, so the guarantee no longer depends on every
-- deployment being current. Deploying Werkpages applies it; this script remains
-- for correcting production BEFORE that deployment, and stays re-runnable
-- afterwards - on clean data it reports nothing and changes nothing.
--
-- The undo is db/preflight/rollback_v108_seed_classification.sql. Both write the
-- same backup table, so whichever ran forward, that script rolls it back.
--
-- Transactional throughout. Idempotent.
-- ─────────────────────────────────────────────────────────────────────────────

\set ON_ERROR_STOP on
BEGIN;

-- ── 0. This is a POST-V106 step ─────────────────────────────────────────────
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '106' AND success) THEN
    RAISE EXCEPTION 'V106 has not applied, so reviews.origin carries no classification yet; '
                    'this script has nothing to reclassify and must not run';
  END IF;
END $$;

-- ── 1. A backup of exactly the rows this touches ────────────────────────────
-- Before, not after. origin is the only column that changes, so this table plus
-- the recalculation in step 4 is the whole undo:
--   UPDATE reviews r SET origin = b.origin
--     FROM reviews_seed_reclassify_backup b WHERE b.id = r.id;
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

-- This run's targets, kept apart from the cumulative backup table so the report
-- at the end can say what THIS run changed rather than what every run has ever
-- changed. A re-run on clean data must be able to say "nothing".
CREATE TEMP TABLE this_run ON COMMIT DROP AS
SELECT r.id
  FROM reviews r
 WHERE r.origin  = 'contributor'
   AND r.weight  = TRUE
   AND r.user_id IS NULL;

-- ── 2. What is about to change ──────────────────────────────────────────────
\echo '=============================================================='
\echo 'UNCLASSIFIED SEEDS (origin contributor, weight TRUE, no user_id)'
\echo 'created_at is INVENTED by createSeedReview (1-179 days back), so it is'
\echo 'NOT the insert time and says nothing about when this row arrived.'
\echo '=============================================================='
SELECT r.id,
       r.manager_id,
       m.name            AS manager_name,
       m.approval_status,
       r.author,
       r.overall_rating,
       r.weight_expires_on,
       r.created_at      AS invented_created_at
  FROM reviews r
  JOIN managers m ON m.id = r.manager_id
 WHERE r.origin  = 'contributor'
   AND r.weight  = TRUE
   AND r.user_id IS NULL
 ORDER BY r.manager_id;

\echo '--- the manager cache as it stands, BEFORE reclassification ---'
SELECT m.id, m.name, m.reviews_count, m.overall_rating
  FROM managers m
 WHERE m.id IN (SELECT manager_id FROM reviews
                 WHERE origin = 'contributor' AND weight = TRUE AND user_id IS NULL)
 ORDER BY m.id;

-- ── 3. The reclassification ─────────────────────────────────────────────────
-- The predicate is V106's backfill predicate, unchanged, and it rests on the
-- same claim V106 verified by grepping every INSERT and UPDATE touching
-- `weight`: `user_id IS NULL AND weight = TRUE` is written by createSeedReview
-- and by nothing else. A genuine submission takes the column default, FALSE.
UPDATE reviews
   SET origin = 'platform_example'
 WHERE origin  = 'contributor'
   AND weight  = TRUE
   AND user_id IS NULL;

-- ── 4. The manager cache, recomputed the way recalculate() computes it ──────
-- An example contributes nothing, so every manager this touched is now carrying
-- a cached average and count that include a demonstration. Nothing recomputes
-- that on its own: recalculate() runs on a rating write, and no rating was
-- written here.
--
-- This mirrors ManagerRepository.recalculate exactly, which matters in three
-- places that are easy to get wrong:
--   * the predicate is ReviewSql.genuine, which is
--     `disposition = 'live' AND deleted_at IS NULL AND origin = 'contributor'`;
--   * at zero genuine ratings, overall_rating and category_averages go NULL,
--     not 0 and not an object of zeroes;
--   * a NULL category average inside a nonzero count becomes 0.0, matching
--     nullSafe().
--
-- The UPDATE on managers fires the V89 statement-level trigger, so
-- company_stats_live, company and industry statistics and rankings follow
-- inside this transaction without anything else being touched.
WITH affected AS (
  SELECT DISTINCT manager_id FROM reviews_seed_reclassify_backup
),
genuine AS (
  SELECT a.manager_id,
         count(r.id)::INTEGER                                          AS n,
         round(avg(r.overall_rating)::NUMERIC, 1)                      AS overall,
         round(avg(r.communication_style)::NUMERIC, 1)                 AS c01,
         round(avg(r.perceived_approachability)::NUMERIC, 1)           AS c02,
         round(avg(r.perceived_clarity_of_expectations)::NUMERIC, 1)   AS c03,
         round(avg(r.feedback_style)::NUMERIC, 1)                      AS c04,
         round(avg(r.perceived_supportiveness)::NUMERIC, 1)            AS c05,
         round(avg(r.decision_making_style)::NUMERIC, 1)               AS c06,
         round(avg(r.organization_and_planning_style)::NUMERIC, 1)     AS c07,
         round(avg(r.delegation_style)::NUMERIC, 1)                    AS c08,
         round(avg(r.perceived_professional_demeanor)::NUMERIC, 1)     AS c09,
         round(avg(r.overall_working_experience)::NUMERIC, 1)          AS c10
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

\echo '--- the manager cache AFTER, examples excluded ---'
SELECT m.id, m.name, m.reviews_count, m.overall_rating
  FROM managers m
 WHERE m.id IN (SELECT manager_id FROM reviews_seed_reclassify_backup)
 ORDER BY m.id;

-- ── 5. Rows this deliberately did not touch ─────────────────────────────────
DO $$
DECLARE
  v_fixed     BIGINT;
  v_remaining BIGINT;
  v_ambiguous BIGINT;
BEGIN
  SELECT count(*) INTO v_fixed     FROM this_run;
  SELECT count(*) INTO v_remaining FROM reviews
   WHERE origin = 'contributor' AND weight = TRUE AND user_id IS NULL;
  SELECT count(*) INTO v_ambiguous FROM reviews
   WHERE weight = TRUE AND user_id IS NOT NULL;

  IF v_fixed = 0 THEN
    RAISE NOTICE 'No unclassified seeds found. Nothing was reclassified; the cache '
                 'recomputation below is a no-op repair of previously fixed managers.';
  ELSE
    RAISE NOTICE '% seed(s) reclassified as platform examples by THIS run', v_fixed;
  END IF;

  IF v_remaining > 0 THEN
    RAISE EXCEPTION 'reclassification did not take: % unclassified seeds remain', v_remaining;
  END IF;

  IF v_ambiguous > 0 THEN
    RAISE NOTICE '% row(s) carry weight = TRUE WITH a user_id. NOT touched, by design: no '
                 'write path produces that shape, so each is a question rather than a seed. '
                 'Investigate before treating any of them as examples.', v_ambiguous;
  END IF;
END $$;

-- ── 6. What the admin tile will read, and why ────────────────────────────
-- THIS GATE DOES NOT DEMAND ZERO, and the first draft of it did, which was
-- wrong. The tile counts `origin = 'contributor' AND weight = TRUE` inside the
-- weighting window, and an ambiguous row (step 5) satisfies that predicate too.
-- Demanding zero therefore rolled the whole script back over a row it had
-- deliberately declined to touch, discarding a correct reclassification in
-- protest at a separate unresolved question. Caught by running it against a
-- fixture carrying one of each.
--
-- So: the script is accountable for unclassified SEEDS, and step 5 proves those
-- are gone. Here it accounts for the tile, and refuses only if the remainder is
-- something it cannot explain.
DO $$
DECLARE
  v_tile      BIGINT;
  v_ambiguous BIGINT;
BEGIN
  SELECT count(*) INTO v_tile FROM reviews
   WHERE origin = 'contributor' AND weight = TRUE
     AND (weight_expires_on IS NULL OR weight_expires_on > CURRENT_DATE);

  SELECT count(*) INTO v_ambiguous FROM reviews
   WHERE origin = 'contributor' AND weight = TRUE AND user_id IS NOT NULL
     AND (weight_expires_on IS NULL OR weight_expires_on > CURRENT_DATE);

  IF v_tile = 0 THEN
    RAISE NOTICE 'Weighted Opinions now reads 0.';
  ELSIF v_tile = v_ambiguous THEN
    RAISE NOTICE 'Weighted Opinions will read %, and all of them are the ambiguous rows '
                 'reported above, which this script does not touch. Every seed is '
                 'classified. Resolve those rows separately and the tile returns to 0.',
                 v_tile;
  ELSE
    RAISE EXCEPTION 'Weighted Opinions would read %, of which only % are explained by '
                    'ambiguous rows. The remainder is unaccounted for, so nothing is '
                    'committed.', v_tile, v_ambiguous;
  END IF;
END $$;

-- ── 7. the dependent statistics, recomputed and then CHECKED ───────────────
-- Step 4's UPDATE on `managers` fires V89's statement trigger and step 3's UPDATE
-- on `reviews` fires V101's, so both projections should already have followed.
-- This does not trust that: it recomputes them from source data for every
-- affected company and asserts the recomputation changed nothing.
--
-- There is no separate industry projection. `companies.industry` is a column and
-- industry figures aggregate from company stats at query time, so correcting
-- company stats corrects them by construction.
CREATE TEMP TABLE stats_before ON COMMIT DROP AS
SELECT s.company_id, s.manager_count, s.total_reviews, s.avg_rating
  FROM company_stats_live s
 WHERE s.company_id IN (
     SELECT DISTINCT m.company_id FROM managers m
      WHERE m.id IN (SELECT manager_id FROM reviews_seed_reclassify_backup)
        AND m.company_id IS NOT NULL);

DO $$
DECLARE
  r       RECORD;
  v_ids   BIGINT[];
  v_drift BIGINT;
BEGIN
  SELECT array_agg(DISTINCT m.company_id) INTO v_ids
    FROM managers m
   WHERE m.id IN (SELECT manager_id FROM reviews_seed_reclassify_backup)
     AND m.company_id IS NOT NULL;

  IF v_ids IS NULL THEN
    RAISE NOTICE 'No affected company, so no statistics to recompute.';
    RETURN;
  END IF;

  FOR r IN SELECT unnest(v_ids) AS company_id LOOP
    PERFORM refresh_company_stats(r.company_id);
  END LOOP;
  PERFORM company_employment_stats_apply(v_ids);

  SELECT count(*) INTO v_drift
    FROM stats_before b
    JOIN company_stats_live s ON s.company_id = b.company_id
   WHERE b.manager_count IS DISTINCT FROM s.manager_count
      OR b.total_reviews IS DISTINCT FROM s.total_reviews
      OR b.avg_rating    IS DISTINCT FROM s.avg_rating;

  IF v_drift > 0 THEN
    RAISE NOTICE '% company stat row(s) were STALE and have been corrected. The triggers that '
                 'should have followed the reclassification did not, which is worth '
                 'investigating separately - the figures are right now either way.', v_drift;
  ELSE
    RAISE NOTICE 'Company statistics for % company(ies) verified against source data.',
                 array_length(v_ids, 1);
  END IF;
END $$;

\echo '--- company statistics for the affected employers, after ---'
SELECT s.company_id, c.name, s.manager_count, s.total_reviews, s.avg_rating
  FROM company_stats_live s
  JOIN companies c ON c.id = s.company_id
 WHERE s.company_id IN (
     SELECT DISTINCT m.company_id FROM managers m
      WHERE m.id IN (SELECT manager_id FROM reviews_seed_reclassify_backup)
        AND m.company_id IS NOT NULL)
 ORDER BY s.company_id;

COMMIT;
