-- ─────────────────────────────────────────────────────────────────────────────
-- V107  RETIRE STRANDED EXAMPLES, AND RELABEL ERASED BYLINES
--
-- Two data corrections the application fixes of 2026-11-13 cannot reach, because
-- those fixes act when a rating is SUBMITTED and these rows are waiting for a
-- submission that may never arrive.
--
--   1. a platform example sitting beside a genuine rating. The application now
--      deletes the example as the genuine rating lands; before that it either
--      set a 14-day expiry or did nothing at all, and the code records 304
--      placeholders with no countdown ever started.
--
--   2. reviews.author = 'Anonymous User', written by account erasure. No live
--      account carries that string, so it disclosed which ratings belonged to
--      somebody who had deleted themselves - the one fact the erasure exists to
--      remove. The application now mints a plausible handle instead.
--
-- Idempotent: re-running changes nothing, because both statements are scoped by
-- the condition they remove.
--
-- THIS IS NOT THE VEHICLE FOR THE EXISTING PRODUCTION DATA. Production is at V96,
-- where `origin` does not exist, where the deployed recalculate counts a placeholder
-- whose countdown never started, and where the author dedup index has no exemption for
-- erased rows. The script pair in ../preflight/prod_at_v96_{inspect,apply}_examples_and
-- _bylines.sql makes these same two corrections against that schema, with backups and a
-- per-row retry on the unique index. By the time this migration runs, it should find
-- only what accumulated after that script.
--
-- `origin` IS SAFE TO NAME HERE, unlike in the application's own SQL. Flyway
-- applies in order, so V106 has necessarily run by the time this does and the
-- column necessarily exists. The application cannot assume that, which is why
-- deleteSeedReview and the profile projection both use the weight/user_id pair
-- instead. Here the provenance column is available and is the better test.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. STRANDED EXAMPLES ────────────────────────────────────────────────────
--
-- TWO POPULATIONS, AND ONLY ONE OF THEM IS A DEFECT.
--
--   example AND a genuine rating  -> delete the example          (this block)
--   example ONLY                  -> KEEP it, labelled in the UI (untouched)
--
-- The second must not be touched. An example-only profile is a deliberate
-- product state: the "Example rating" and "Example score" pills exist precisely
-- to label it. Deleting those examples would strip every such profile back to
-- no rating at all, which is the opposite of the intent.

-- ORIGIN IS THE WHOLE TEST FOR "A REAL RATING", and nothing else may be added to it.
-- This read `g.user_id IS NOT NULL AND g.origin = 'contributor'`, and the first half was
-- wrong: an anonymous drop-off rating carries no user_id and is entirely genuine, so a
-- manager whose only real rating arrived that way was not flagged and kept its example
-- sitting beside it. Caught on a V96 replica built from the migrations, where the
-- population it misses is one of nine fixtures.
CREATE TEMPORARY TABLE _v107_affected AS
SELECT DISTINCT e.manager_id
  FROM reviews e
 WHERE e.weight = TRUE
   AND e.user_id IS NULL
   AND e.deleted_at IS NULL                   -- already withdrawn: not ours to hard delete
   AND EXISTS (
         SELECT 1
           FROM reviews g
          WHERE g.manager_id  = e.manager_id
            AND g.origin      = 'contributor' -- not another example; signed or anonymous
            AND g.disposition = 'live'        -- not held or rejected
            AND g.deleted_at IS NULL);

DO $$
DECLARE v_m BIGINT; v_e BIGINT; v_only BIGINT; v_anon BIGINT;
BEGIN
  SELECT count(*) INTO v_m FROM _v107_affected;
  SELECT count(*) INTO v_e FROM reviews r JOIN _v107_affected a USING (manager_id)
   WHERE r.weight = TRUE AND r.user_id IS NULL AND r.deleted_at IS NULL;
  SELECT count(DISTINCT e.manager_id) INTO v_only FROM reviews e
   WHERE e.weight = TRUE AND e.user_id IS NULL AND e.deleted_at IS NULL
     AND NOT EXISTS (SELECT 1 FROM reviews g
                      WHERE g.manager_id = e.manager_id
                        AND g.origin = 'contributor' AND g.disposition = 'live'
                        AND g.deleted_at IS NULL);
  SELECT count(*) INTO v_anon FROM reviews WHERE author = 'Anonymous User';
  RAISE NOTICE 'V107: deleting % example(s) across % manager(s)', v_e, v_m;
  RAISE NOTICE 'V107: leaving % example-only manager(s) alone, by design', v_only;
  RAISE NOTICE 'V107: relabelling % erased byline(s)', v_anon;
END $$;

DELETE FROM reviews r
 USING _v107_affected a
 WHERE r.manager_id = a.manager_id
   AND r.weight     = TRUE
   AND r.user_id   IS NULL
   AND r.deleted_at IS NULL;

-- ── 1b. RECALCULATE, which is NOT optional ──────────────────────────────────
--
-- Before V106 a seed counted toward the cached average through the weight
-- window that preceded `origin`. Deleting the row without this would leave
-- managers.overall_rating and reviews_count describing a row that is gone, with
-- nothing to point at the discrepancy.
--
-- Mirrors ManagerRepository.recalculate exactly: the same three columns, the
-- same genuine-only definition, overall_rating and category_averages NULL when
-- the count is zero, and category_averages keyed by the display names the
-- frontend reads. A different shape here would be a second definition of a
-- manager's rating, which is the thing that file's comments warn against.
--
-- company_stats_live follows inside this statement: the managers trigger fires
-- on reviews_count and overall_rating (V89/V90).

UPDATE managers m
   SET overall_rating    = CASE WHEN s.cnt_genuine = 0 THEN NULL ELSE s.avg_overall END
     , reviews_count     = s.cnt_genuine
     -- coalesce to 0, because ManagerRepository.recalculate passes every category
     -- through nullSafe(), which turns a null average into 0.0 rather than leaving the
     -- key null. A rating that filled in only the overall score therefore produced a
     -- different JSON shape here than the application writes, and the frontend reads
     -- these keys.
     , category_averages = CASE WHEN s.cnt_genuine = 0 THEN NULL ELSE jsonb_build_object(
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
           count(r.id)                                                AS cnt_genuine,
           round(avg(r.overall_rating)::numeric, 1)                    AS avg_overall,
           round(avg(r.communication_style)::numeric, 1)               AS c_comm,
           round(avg(r.perceived_approachability)::numeric, 1)         AS c_appr,
           round(avg(r.perceived_clarity_of_expectations)::numeric, 1) AS c_clar,
           round(avg(r.feedback_style)::numeric, 1)                    AS c_feed,
           round(avg(r.perceived_supportiveness)::numeric, 1)          AS c_supp,
           round(avg(r.decision_making_style)::numeric, 1)             AS c_deci,
           round(avg(r.organization_and_planning_style)::numeric, 1)   AS c_orga,
           round(avg(r.delegation_style)::numeric, 1)                  AS c_dele,
           round(avg(r.perceived_professional_demeanor)::numeric, 1)   AS c_demo,
           round(avg(r.overall_working_experience)::numeric, 1)        AS c_expe
      FROM _v107_affected a
      LEFT JOIN reviews r
             ON r.manager_id  = a.manager_id
            AND r.disposition = 'live'
            AND r.deleted_at IS NULL
            AND r.origin      = 'contributor'
     GROUP BY a.manager_id
  ) s
 WHERE m.id = s.manager_id;

DROP TABLE _v107_affected;

-- ── 2. ERASED BYLINES ───────────────────────────────────────────────────────
--
-- ONE NAME PER ROW, not one per account. A shared handle would relink an erased
-- person's ratings to each other, which is a fact the erasure removed
-- everywhere else.
--
-- THE VOCABULARY IS DUPLICATED HERE, and that is the one thing to dislike about
-- this migration. It lives in org.werkpages.repository.Pseudonyms (and its RMM
-- twin) so that there is a single list; this is a second copy, in SQL, because
-- the alternative is a one-off application task that leaves no record in the
-- migration history that it ran. If the list in Pseudonyms is ever changed,
-- this file does not need updating - these rows are already named - but a third
-- copy must never be made.

UPDATE reviews
   SET author = (ARRAY['Brave','Swift','Bold','Calm','Keen','Wise','Fair','Kind',
                       'Sharp','Quiet','Clear','Warm','Cool','Bright','Loyal'])
                [1 + floor(random() * 15)::int]
             || (ARRAY['Falcon','Tiger','Eagle','Wolf','Bison','Crane','Lynx',
                       'Otter','Raven','Gecko','Heron','Panda','Finch','Moose'])
                [1 + floor(random() * 14)::int]
             || (10 + floor(random() * 90)::int)::text
     , updated_at = now()
 WHERE author = 'Anonymous User';

-- author_erased is deliberately NOT touched. It stays TRUE and keeps doing its
-- job: holding these rows out of the legacy author dedup index so that two
-- people who rated the same manager under the same title and employer can both
-- erase themselves. The flag does that, not the byline, so varying the byline
-- costs nothing there. (That column is added by V97, not V107 as one code
-- comment claims.)
--
-- No recalculation follows: author feeds no statistic.
