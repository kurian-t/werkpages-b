-- ─────────────────────────────────────────────────────────────────────────────
-- APPLY: retire stranded platform examples, relabel erased bylines.
--
-- ONE TRANSACTION. It lands completely or not at all.
--
-- NOT a migration. Flyway does not look in this directory, and nothing here is
-- recorded in flyway_schema_history. Run it by hand, once, and read the output.
--
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
--        -f prod_at_v96_apply_examples_and_bylines.sql
--
-- RUN THE INSPECT SCRIPT BESIDE THIS ONE FIRST. It is read only, it prints
-- every profile whose number changes and what it changes to, and it carries
-- the explanation of why V107 cannot do this job on a V96 database.
--
-- WHAT IT DOES
--
--   1. deletes a platform placeholder that sits on a manager ALREADY holding a
--      live real rating, and recalculates that manager's cached statistics;
--   2. replaces reviews.author = 'Anonymous User' with a plausible minted
--      handle, one per row.
--
-- WHAT IT DELIBERATELY DOES NOT DO
--
--   a placeholder that is a profile's ONLY rating is left exactly as it is.
--   That is a deliberate product state, labelled in the interface by the
--   "Example rating" and "Example score" pills. Deleting those would strip
--   every such profile back to no rating at all.
--
-- REVERSIBLE. Every deleted row is copied into backfill_v96_deleted_examples
-- first, and every manager's prior statistics into
-- backfill_v96_manager_stats_before, with the review ids whose byline changed
-- in backfill_v96_relabelled_bylines. Restoring is an INSERT and two UPDATEs
-- from those three tables; the recipe is at the end of this file.
--
-- IDEMPOTENT. A second run finds nothing to do, because both statements are
-- scoped by the condition they remove.
--
-- WHEN TO RUN IT. Mid-morning, not the evening. The site takes real searches in
-- the evening, and although this holds row locks only on the rows it touches, the
-- UPDATE on managers fires the company_stats_live triggers for each affected
-- company. There is no reason for that to land on live traffic.
--
-- THIS DOES NOT STOP EITHER DEFECT RECURRING. Production's deployed code still
-- writes 'Anonymous User' on every account erasure, and still leaves a
-- placeholder behind on the submission paths that do not call
-- scheduleSeedExpiry. The code that fixes both is written and unshipped. This
-- script clears the accumulated rows; the deploy is what closes the tap.
-- ─────────────────────────────────────────────────────────────────────────────

\pset pager off
\timing on

BEGIN;

-- ── 0. REFUSE TO RUN ON THE WRONG SCHEMA ────────────────────────────────────
--
-- The two corrections are also made by V107, against the post-V106 schema and
-- with the post-V106 definitions. If both ever act on one database the second
-- is a no-op, but the recalculation definitions differ, so running THIS one on
-- a V106+ database would write counts the shipped code disagrees with. Refuse,
-- rather than rely on the operator having read the header.

DO $guard$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
              WHERE table_schema = 'public' AND table_name = 'reviews'
                AND column_name = 'origin') THEN
    RAISE EXCEPTION
      'reviews.origin exists, so this database is at V106 or later. Use the '
      'V107 migration, which is written for that schema. This script is for a '
      'V96 database and its recalculation would contradict the shipped code.';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'public' AND table_name = 'reviews'
                    AND column_name = 'weight_expires_on') THEN
    RAISE EXCEPTION 'reviews.weight_expires_on is missing; this is not the expected schema.';
  END IF;

  RAISE NOTICE 'schema gate passed: V96-shaped, no origin column.';
END $guard$;

-- ── 1. BACKUP, BEFORE ANYTHING IS TOUCHED ───────────────────────────────────

CREATE TABLE IF NOT EXISTS backfill_v96_deleted_examples    (LIKE reviews);
CREATE TABLE IF NOT EXISTS backfill_v96_manager_stats_before (
  manager_id        BIGINT PRIMARY KEY,
  overall_rating    NUMERIC,
  reviews_count     INTEGER,
  category_averages JSONB,
  captured_at       TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS backfill_v96_relabelled_bylines (
  review_id   UUID PRIMARY KEY,
  old_author  TEXT NOT NULL,
  new_author  TEXT NOT NULL,
  captured_at TIMESTAMPTZ NOT NULL DEFAULT now());

-- The managers whose placeholder is stranded: a placeholder AND a live real
-- rating on the same profile.
--
-- A REAL RATING IS weight = FALSE, NOT user_id IS NOT NULL. An anonymous
-- drop-off rating carries no user_id and is entirely genuine, so testing for
-- an owning account would leave the placeholder in place on exactly the
-- profiles whose only real rating came in through the drop-off path.
CREATE TEMPORARY TABLE _affected ON COMMIT DROP AS
SELECT DISTINCT e.manager_id
  FROM reviews e
 WHERE e.weight = TRUE
   AND e.user_id IS NULL
   AND e.deleted_at IS NULL
   AND EXISTS (SELECT 1 FROM reviews g
                WHERE g.manager_id  = e.manager_id
                  AND g.weight      = FALSE
                  AND g.disposition = 'live'
                  AND g.deleted_at IS NULL);

INSERT INTO backfill_v96_manager_stats_before
       (manager_id, overall_rating, reviews_count, category_averages)
SELECT m.id, m.overall_rating, m.reviews_count, m.category_averages
  FROM managers m JOIN _affected a ON a.manager_id = m.id
 WHERE NOT EXISTS (SELECT 1 FROM backfill_v96_manager_stats_before b
                    WHERE b.manager_id = m.id);

INSERT INTO backfill_v96_deleted_examples
SELECT r.* FROM reviews r JOIN _affected a USING (manager_id)
 WHERE r.weight = TRUE AND r.user_id IS NULL AND r.deleted_at IS NULL;

DO $report$
DECLARE v_m BIGINT; v_e BIGINT; v_only BIGINT; v_anon BIGINT;
BEGIN
  SELECT count(*) INTO v_m FROM _affected;
  SELECT count(*) INTO v_e FROM reviews r JOIN _affected a USING (manager_id)
   WHERE r.weight = TRUE AND r.user_id IS NULL AND r.deleted_at IS NULL;
  SELECT count(DISTINCT e.manager_id) INTO v_only FROM reviews e
   WHERE e.weight = TRUE AND e.user_id IS NULL AND e.deleted_at IS NULL
     AND NOT EXISTS (SELECT 1 FROM reviews g
                      WHERE g.manager_id = e.manager_id AND g.weight = FALSE
                        AND g.disposition = 'live' AND g.deleted_at IS NULL);
  SELECT count(*) INTO v_anon FROM reviews WHERE author = 'Anonymous User';
  RAISE NOTICE 'deleting % stranded placeholder(s) across % manager(s)', v_e, v_m;
  RAISE NOTICE 'leaving % placeholder-only manager(s) alone, by design', v_only;
  RAISE NOTICE 'relabelling % erased byline(s)', v_anon;
END $report$;

-- ── 2. DELETE ───────────────────────────────────────────────────────────────

DELETE FROM reviews r
 USING _affected a
 WHERE r.manager_id = a.manager_id
   AND r.weight     = TRUE
   AND r.user_id   IS NULL
   AND r.deleted_at IS NULL;

-- ── 3. RECALCULATE, which is NOT optional ───────────────────────────────────
--
-- On this schema a placeholder with weight_expires_on IS NULL COUNTS toward
-- managers.overall_rating and reviews_count. That is not an accident of the
-- data, it is what the deployed ManagerRepository.recalculate asks for:
--
--     live(r) AND manager_id = $1
--     AND (weight = FALSE OR weight_expires_on IS NULL
--          OR weight_expires_on > CURRENT_DATE)
--
-- which admits a weighted row whose countdown never started. Deleting such a
-- row without recalculating leaves the cached average describing a rating that
-- no longer exists.
--
-- THE FILTER BELOW IS THAT EXPRESSION, DELIBERATELY, not the newer
-- origin = 'contributor' one. The next ordinary submission for this manager
-- will recalculate with the deployed definition, so anything else written here
-- would be overwritten and the number would appear to move on its own.
--
-- COALESCE(..., 0) on each category mirrors the Java nullSafe helper, which
-- puts 0.0 in the JSON rather than null. The frontend reads these keys.
--
-- company_stats_live follows from this statement: its managers trigger fires
-- on reviews_count and overall_rating (V89, V90).

UPDATE managers m
   SET overall_rating    = CASE WHEN s.cnt = 0 THEN NULL ELSE s.avg_overall END
     , reviews_count     = s.cnt
     , category_averages = CASE WHEN s.cnt = 0 THEN NULL ELSE jsonb_build_object(
           'Communication Style',               coalesce(s.c_comm, 0),
           'Perceived Approachability',         coalesce(s.c_appr, 0),
           'Perceived Clarity of Expectations', coalesce(s.c_clar, 0),
           'Feedback Style',                    coalesce(s.c_feed, 0),
           'Perceived Supportiveness',          coalesce(s.c_supp, 0),
           'Decision Making Style',             coalesce(s.c_deci, 0),
           'Organization and Planning Style',   coalesce(s.c_orga, 0),
           'Delegation Style',                  coalesce(s.c_dele, 0),
           'Perceived Professional Demeanor',   coalesce(s.c_demo, 0),
           'Overall Working Experience',        coalesce(s.c_expe, 0)) END
     , updated_at        = now()
  FROM (
    SELECT a.manager_id,
           count(r.id)::INTEGER                                        AS cnt,
           round(avg(r.overall_rating)::numeric, 1)                     AS avg_overall,
           round(avg(r.communication_style)::numeric, 1)                AS c_comm,
           round(avg(r.perceived_approachability)::numeric, 1)          AS c_appr,
           round(avg(r.perceived_clarity_of_expectations)::numeric, 1)  AS c_clar,
           round(avg(r.feedback_style)::numeric, 1)                     AS c_feed,
           round(avg(r.perceived_supportiveness)::numeric, 1)           AS c_supp,
           round(avg(r.decision_making_style)::numeric, 1)              AS c_deci,
           round(avg(r.organization_and_planning_style)::numeric, 1)    AS c_orga,
           round(avg(r.delegation_style)::numeric, 1)                   AS c_dele,
           round(avg(r.perceived_professional_demeanor)::numeric, 1)    AS c_demo,
           round(avg(r.overall_working_experience)::numeric, 1)         AS c_expe
      FROM _affected a
      LEFT JOIN reviews r
             ON r.manager_id  = a.manager_id
            AND r.disposition = 'live'
            AND r.deleted_at IS NULL
            AND (r.weight = FALSE OR r.weight_expires_on IS NULL
                 OR r.weight_expires_on > CURRENT_DATE)
            AND NOT (r.weight = TRUE AND r.user_id IS NULL)
     GROUP BY a.manager_id
  ) s
 WHERE m.id = s.manager_id;

-- ── 4. ERASED BYLINES, one minted handle per row ────────────────────────────
--
-- ONE NAME PER ROW, not one per account. A shared handle would relink an
-- erased person's ratings to each other, which is a fact the erasure removed
-- everywhere else.
--
-- A ROW AT A TIME WITH A RETRY, not one bulk UPDATE, and the reason is this
-- schema specifically. idx_reviews_author_manager_role (V37) is UNIQUE on
-- (lower(trim(author)), manager_id, lower(trim(manager_title)),
-- lower(trim(manager_company))) WHERE weight = FALSE, with no exemption for
-- erased rows. V97 later adds AND NOT author_erased, which is what makes the
-- bulk UPDATE in V107 safe there and unsafe here: a minted handle can collide
-- with a real author on the same manager and role, and one collision in a bulk
-- statement aborts the whole transaction.
--
-- The vocabulary is a third copy of the list in org.werkpages.repository
-- .Pseudonyms, as V107's is a second. It is copied rather than shared because
-- the alternative is a one-off application task that leaves no artifact of
-- having run. If Pseudonyms ever changes, these rows are already named and
-- this file does not need updating. A fourth copy must never be made.

DO $relabel$
DECLARE
  adj  TEXT[] := ARRAY['Brave','Swift','Bold','Calm','Keen','Wise','Fair','Kind',
                       'Sharp','Quiet','Clear','Warm','Cool','Bright','Loyal'];
  ani  TEXT[] := ARRAY['Falcon','Tiger','Eagle','Wolf','Bison','Crane','Lynx',
                       'Otter','Raven','Gecko','Heron','Panda','Finch','Moose'];
  r        RECORD;
  cand     TEXT;
  attempt  INT;
  settled  BOOLEAN;
  n        INT := 0;
  collided INT := 0;
BEGIN
  FOR r IN SELECT id FROM reviews WHERE author = 'Anonymous User' ORDER BY id LOOP
    settled := FALSE;
    FOR attempt IN 1..200 LOOP
      cand := adj[1 + floor(random() * array_length(adj, 1))::int]
           || ani[1 + floor(random() * array_length(ani, 1))::int]
           || (10 + floor(random() * 90)::int)::text;
      BEGIN
        UPDATE reviews SET author = cand, updated_at = now() WHERE id = r.id;
        INSERT INTO backfill_v96_relabelled_bylines (review_id, old_author, new_author)
        VALUES (r.id, 'Anonymous User', cand)
        ON CONFLICT (review_id) DO UPDATE SET new_author = EXCLUDED.new_author;
        settled := TRUE;
        EXIT;
      EXCEPTION WHEN unique_violation THEN
        -- that handle already belongs to another rating of this manager and
        -- role. Not an error: mint another. Counted so the output says it
        -- happened rather than hiding it.
        collided := collided + 1;
      END;
    END LOOP;
    IF NOT settled THEN
      RAISE EXCEPTION 'could not mint a free handle for review % after 200 attempts; '
                      'the vocabulary is exhausted for this manager and role', r.id;
    END IF;
    n := n + 1;
  END LOOP;
  RAISE NOTICE 'relabelled % byline(s), after % collision(s)', n, collided;
END $relabel$;

-- author feeds no statistic, so no recalculation follows this.

-- ── 5. WHAT THE TRANSACTION IS ABOUT TO COMMIT ──────────────────────────────

DO $verify$
DECLARE v_left BIGINT; v_anon BIGINT; v_bad BIGINT;
BEGIN
  SELECT count(*) INTO v_left FROM reviews r
   WHERE r.weight = TRUE AND r.user_id IS NULL AND r.deleted_at IS NULL
     AND EXISTS (SELECT 1 FROM reviews g WHERE g.manager_id = r.manager_id
                   AND g.weight = FALSE AND g.disposition = 'live'
                   AND g.deleted_at IS NULL);
  SELECT count(*) INTO v_anon FROM reviews WHERE author = 'Anonymous User';
  SELECT count(*) INTO v_bad FROM managers m JOIN backfill_v96_manager_stats_before b
                                               ON b.manager_id = m.id
   WHERE m.reviews_count = 0 OR m.overall_rating IS NULL;

  RAISE NOTICE 'stranded placeholders remaining: % (expected 0)', v_left;
  RAISE NOTICE 'Anonymous User bylines remaining: % (expected 0)', v_anon;
  RAISE NOTICE 'touched profiles now showing no rating: % (expected 0)', v_bad;

  IF v_left <> 0 OR v_anon <> 0 OR v_bad <> 0 THEN
    RAISE EXCEPTION 'post-conditions not met, rolling back. Nothing has changed.';
  END IF;
END $verify$;

COMMIT;

\echo ''
\echo 'COMMITTED. Before and after, for every profile that moved:'

SELECT m.id, left(m.name, 28) AS manager,
       b.reviews_count  AS count_before, m.reviews_count  AS count_after,
       b.overall_rating AS rating_before, m.overall_rating AS rating_after
  FROM backfill_v96_manager_stats_before b JOIN managers m ON m.id = b.manager_id
 ORDER BY abs(coalesce(b.overall_rating, 0) - coalesce(m.overall_rating, 0)) DESC, m.id
 LIMIT 50;

\echo ''
\echo 'Backups kept in backfill_v96_deleted_examples, backfill_v96_manager_stats_before'
\echo 'and backfill_v96_relabelled_bylines. Drop them once the result has been seen in'
\echo 'the product, not before.'

-- ─────────────────────────────────────────────────────────────────────────────
-- TO REVERT, in one transaction:
--
--   BEGIN;
--   INSERT INTO reviews SELECT * FROM backfill_v96_deleted_examples
--     ON CONFLICT (id) DO NOTHING;
--   UPDATE managers m SET overall_rating = b.overall_rating,
--                         reviews_count  = b.reviews_count,
--                         category_averages = b.category_averages,
--                         updated_at = now()
--     FROM backfill_v96_manager_stats_before b WHERE m.id = b.manager_id;
--   UPDATE reviews r SET author = b.old_author
--     FROM backfill_v96_relabelled_bylines b
--    WHERE r.id = b.review_id AND r.author = b.new_author;
--   COMMIT;
--
-- The byline revert restores the disclosure this script exists to remove, so
-- revert that part only if something is actually wrong with it.
-- ─────────────────────────────────────────────────────────────────────────────
