-- ─────────────────────────────────────────────────────────────────────────────
-- INSPECT: stranded platform examples, and erased bylines.  READ ONLY.
--
-- Run this against production BEFORE the apply script beside it, and read the
-- output. It writes nothing, creates nothing and holds no locks beyond the
-- snapshots its own queries take.
--
-- NOT a migration. Flyway does not look in this directory.
--
-- WHY A SEPARATE SCRIPT EXISTS AT ALL, rather than just deploying V107.
--
-- V107__retire_stranded_examples_and_erased_bylines.sql makes these same two
-- corrections, and it is the right vehicle once the Rev 268 stream ships. It
-- cannot be the vehicle today, for three independent reasons:
--
--   1. Production is at V96. V107 reads reviews.origin, which V106 adds, so on
--      production it fails on its first statement with
--      'column "origin" does not exist'. Nothing partial, but nothing done.
--
--   2. V107 recalculates with the POST-V106 definition of a countable rating
--      (origin = 'contributor'). Production's running jar uses the PRE-V106
--      one: live, not deleted, and not an EXPIRED weighted row. A placeholder
--      with weight_expires_on IS NULL is therefore counted on production
--      today. Recalculating with the wrong definition would write a number the
--      running code disagrees with, and the next submission for that manager
--      would silently move it again.
--
--   3. V107's bulk relabel of 'Anonymous User' is safe on a V97+ schema,
--      because there the author dedup index carries AND NOT author_erased.
--      At V96 that index is unconditional on weight = FALSE, so a randomly
--      minted handle can collide with a real author on the same manager and
--      role. The apply script mints per row and retries on collision.
--
-- The apply script is scoped to V96 and refuses to run on anything later, so
-- the two cannot both act on the same database.
-- ─────────────────────────────────────────────────────────────────────────────

\pset pager off

\echo '=============================================================='
\echo ' 0. SCHEMA, so the rest of the output can be trusted'
\echo '=============================================================='

-- A DO BLOCK WITH EXECUTE, not a plain SELECT. PostgreSQL resolves every relation in a
-- statement at parse time, so naming flyway_schema_history directly makes the whole
-- report fail on a database that was built by hand rather than by Flyway. Dynamic SQL
-- is the only way to ask whether a table exists and read it in one script.
DO $schema$
DECLARE v TEXT;
BEGIN
  IF to_regclass('public.flyway_schema_history') IS NULL THEN
    v := 'no flyway history table';
  ELSE
    EXECUTE 'SELECT max(version::numeric)::text FROM flyway_schema_history WHERE success' INTO v;
  END IF;
  RAISE NOTICE 'flyway version .......................................... %', v;

  RAISE NOTICE 'reviews.origin exists (expected f at V96) ................ %',
    EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema='public' AND table_name='reviews' AND column_name='origin');
  RAISE NOTICE 'reviews.author_erased exists (expected f at V96) ......... %',
    EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema='public' AND table_name='reviews' AND column_name='author_erased');
  RAISE NOTICE 'author dedup index exempts erased rows (expected f) ...... %',
    (SELECT coalesce(bool_or(indexdef LIKE '%author_erased%'), FALSE)
       FROM pg_indexes WHERE indexname LIKE 'idx_reviews_author_manager_role%');
  RAISE NOTICE 'one-placeholder-per-manager index present ............... %',
    EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_one_seed_per_manager');

  IF to_regclass('cron.job') IS NULL THEN
    v := 'no cron schema';
  ELSE
    EXECUTE $q$SELECT count(*)::text FROM cron.job
                WHERE jobname = 'cleanup-expired-weight-reviews'$q$ INTO v;
  END IF;
  RAISE NOTICE 'pg_cron placeholder cleanup job ......................... %', v;
END $schema$;

\echo ''
\echo '=============================================================='
\echo ' 1. THE PLACEHOLDER POPULATION, split by what should happen'
\echo '=============================================================='
\echo '   stranded  = a placeholder on a manager that ALREADY holds a real'
\echo '               rating. This is the defect. The apply script deletes it.'
\echo '   only      = a placeholder that is the manager profile its whole'
\echo '               rating. DELIBERATE product state, labelled in the UI by'
\echo '               the "Example rating" and "Example score" pills. LEFT ALONE.'

WITH ex AS (
  SELECT r.id, r.manager_id, r.weight_expires_on,
         EXISTS (SELECT 1 FROM reviews g
                  WHERE g.manager_id  = r.manager_id
                    AND g.weight      = FALSE
                    AND g.disposition = 'live'
                    AND g.deleted_at IS NULL) AS has_real
    FROM reviews r
   WHERE r.weight = TRUE AND r.user_id IS NULL AND r.deleted_at IS NULL
)
SELECT CASE WHEN has_real THEN 'stranded' ELSE 'only' END                        AS population,
       CASE WHEN weight_expires_on IS NULL THEN 'no countdown'
            WHEN weight_expires_on > CURRENT_DATE THEN 'countdown running'
            ELSE 'countdown lapsed, cron has not collected it' END               AS countdown,
       count(*)                                                                  AS placeholders,
       count(DISTINCT manager_id)                                                AS managers
  FROM ex
 GROUP BY 1, 2
 ORDER BY 1, 2;

\echo ''
\echo '=============================================================='
\echo ' 2. AMBIGUOUS ROWS, which the apply script deliberately skips'
\echo '=============================================================='
\echo '   weight = TRUE with an owning account is neither a placeholder as'
\echo '   this product writes them nor a plain rating. Expected: 0 rows. Any'
\echo '   output here is untouched by the apply script, by design, and is'
\echo '   worth understanding before the Rev 268 stream classifies it.'

SELECT id, manager_id, user_id, author, disposition, weight_expires_on, created_at
  FROM reviews
 WHERE weight = TRUE AND user_id IS NOT NULL
 ORDER BY created_at DESC
 LIMIT 20;

\echo ''
\echo '=============================================================='
\echo ' 3. EXACTLY WHICH PROFILES CHANGE NUMBER, and from what to what'
\echo '=============================================================='
\echo '   "after" is computed with PRODUCTION''S OWN definition of a countable'
\echo '   rating, the one the running jar applies in ManagerRepository'
\echo '   .recalculate: live, not deleted, and not an expired weighted row.'
\echo '   So these are the values the next ordinary submission would also'
\echo '   produce, which is the point: the number must not move twice.'

WITH affected AS (
  SELECT DISTINCT e.manager_id
    FROM reviews e
   WHERE e.weight = TRUE AND e.user_id IS NULL AND e.deleted_at IS NULL
     AND EXISTS (SELECT 1 FROM reviews g
                  WHERE g.manager_id  = e.manager_id
                    AND g.weight      = FALSE
                    AND g.disposition = 'live'
                    AND g.deleted_at IS NULL)
), after AS (
  SELECT a.manager_id,
         count(r.id)                              AS cnt,
         round(avg(r.overall_rating)::numeric, 1) AS avg_overall
    FROM affected a
    LEFT JOIN reviews r
           ON r.manager_id  = a.manager_id
          AND r.disposition = 'live'
          AND r.deleted_at IS NULL
          AND (r.weight = FALSE OR r.weight_expires_on IS NULL
               OR r.weight_expires_on > CURRENT_DATE)
          AND NOT (r.weight = TRUE AND r.user_id IS NULL)
   GROUP BY a.manager_id
)
SELECT m.id, left(m.name, 28) AS manager, left(m.company, 22) AS company,
       m.approval_status,
       m.reviews_count   AS count_before, f.cnt         AS count_after,
       m.overall_rating  AS rating_before, f.avg_overall AS rating_after,
       CASE WHEN f.cnt = 0 THEN 'PROFILE LOSES ITS RATING, INVESTIGATE' ELSE '' END AS flag
  FROM after f JOIN managers m ON m.id = f.manager_id
 ORDER BY abs(coalesce(m.overall_rating, 0) - coalesce(f.avg_overall, 0)) DESC,
          m.id
 LIMIT 100;

\echo ''
\echo '   (list capped at 100 rows; the totals in section 1 are authoritative)'
\echo ''
\echo '=============================================================='
\echo ' 4. SAFETY CHECK: any profile that would end with no rating'
\echo '=============================================================='
\echo '   Expected: 0. A manager only qualifies for deletion BECAUSE it holds'
\echo '   a live real rating, so a zero here would mean the two definitions in'
\echo '   this script disagree. Do not run the apply script if this returns'
\echo '   anything.'

WITH affected AS (
  SELECT DISTINCT e.manager_id
    FROM reviews e
   WHERE e.weight = TRUE AND e.user_id IS NULL AND e.deleted_at IS NULL
     AND EXISTS (SELECT 1 FROM reviews g
                  WHERE g.manager_id  = e.manager_id
                    AND g.weight      = FALSE
                    AND g.disposition = 'live'
                    AND g.deleted_at IS NULL)
)
SELECT count(*) AS profiles_left_with_no_rating
  FROM (
    SELECT a.manager_id
      FROM affected a
      LEFT JOIN reviews r
             ON r.manager_id  = a.manager_id
            AND r.disposition = 'live'
            AND r.deleted_at IS NULL
            AND (r.weight = FALSE OR r.weight_expires_on IS NULL
                 OR r.weight_expires_on > CURRENT_DATE)
            AND NOT (r.weight = TRUE AND r.user_id IS NULL)
     GROUP BY a.manager_id
    HAVING count(r.id) = 0) z;

\echo ''
\echo '=============================================================='
\echo ' 5. ERASED BYLINES'
\echo '=============================================================='
\echo '   Account erasure writes reviews.author = ''Anonymous User''. No live'
\echo '   account carries that string, so it announces which ratings belong to'
\echo '   somebody who deleted themselves, which is the one fact the erasure'
\echo '   exists to remove. The apply script mints a plausible handle per row.'

SELECT (SELECT count(*) FROM reviews WHERE author = 'Anonymous User')         AS manager_ratings,
       (SELECT count(*) FROM reviews
         WHERE author = 'Anonymous User' AND weight = FALSE)                  AS of_those_under_the_unique_index,
       (SELECT count(DISTINCT manager_id) FROM reviews
         WHERE author = 'Anonymous User')                                     AS across_managers;

\echo ''
\echo '   The same string on the other two rating tables, FOR INFORMATION ONLY.'
\echo '   Account erasure writes the literal only to reviews, at one site in'
\echo '   UserRepository, so both of these are expected to be 0 and the apply'
\echo '   script does not touch either table. A non-zero count would be an'
\echo '   author somebody typed, not an erasure, and is not ours to rewrite.'

SELECT 'company_reviews'   AS tbl,
       (SELECT count(*) FROM company_reviews   WHERE author = 'Anonymous User') AS rows
UNION ALL
SELECT 'interview_reviews',
       (SELECT count(*) FROM interview_reviews WHERE author = 'Anonymous User');

\echo ''
\echo '=============================================================='
\echo ' DONE. Nothing was changed.'
\echo '=============================================================='
