-- Ratings per company: manager ratings and company ratings, counted separately.
--
-- READ ONLY. Every statement is a SELECT; nothing here writes, locks or alters anything, so it is
-- safe to run against a replica or against production.
--
-- WHY THIS EXISTS ALONGSIDE ANALYTICS
-- -----------------------------------
-- PostHog counts events from the moment the tracking was added. This counts rows, so it covers
-- everything that was ever saved, including the entire history before any of those events existed.
-- Where the two disagree, this is the one that is right about what the database holds.
--
-- THE TWO KINDS ARE NOT INTERCHANGEABLE
-- -------------------------------------
-- A manager rating is about a person and reaches a company only through managers.company_id.
-- A company rating is about the workplace and carries company_id directly. Summing them produces
-- a number that answers nothing, so they are kept in separate columns throughout.
--
-- VISIBILITY, WHICH IS TWO INDEPENDENT AXES FOR MANAGER RATINGS
-- -------------------------------------------------------------
--   reviews.disposition  live | held | rejected   - whether it was ever published
--   reviews.deleted_at   NULL | timestamp         - whether it was withdrawn or removed
--
-- They are genuinely independent: a deleted review keeps disposition 'live', because deletion is
-- not a publication decision. So "publicly visible" is disposition = 'live' AND deleted_at IS NULL,
-- and counting on either column alone overstates it.
--
-- company_reviews has NO disposition column - a workplace rating is live as soon as it saves - so
-- for those "held" does not exist and is reported as NULL rather than as zero. Zero would claim
-- there are none; NULL says the question does not apply.

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. Per company
-- ─────────────────────────────────────────────────────────────────────────────────────────────
WITH manager_ratings AS (
    SELECT m.company_id,
           count(*) FILTER (WHERE r.disposition = 'live'
                              AND r.deleted_at IS NULL)              AS visible,
           count(*) FILTER (WHERE r.disposition = 'held'
                              AND r.deleted_at IS NULL)              AS held,
           count(*) FILTER (WHERE r.disposition = 'rejected'
                              AND r.deleted_at IS NULL)              AS rejected,
           count(*) FILTER (WHERE r.deleted_at IS NOT NULL)          AS deleted,
           count(*)                                                  AS total_rows,
           count(DISTINCT r.manager_id) FILTER (WHERE r.disposition = 'live'
                                                  AND r.deleted_at IS NULL) AS managers_rated,
           max(r.created_at) FILTER (WHERE r.deleted_at IS NULL)     AS last_rated_at
      FROM reviews r
      JOIN managers m ON m.id = r.manager_id
     WHERE m.company_id IS NOT NULL
     GROUP BY m.company_id
),
company_ratings AS (
    SELECT cr.company_id,
           count(*) FILTER (WHERE cr.deleted_at IS NULL)     AS visible,
           count(*) FILTER (WHERE cr.deleted_at IS NOT NULL) AS deleted,
           count(*)                                          AS total_rows,
           max(cr.created_at) FILTER (WHERE cr.deleted_at IS NULL) AS last_rated_at
      FROM company_reviews cr
     GROUP BY cr.company_id
)
SELECT c.id                                        AS company_id,
       c.name                                      AS company,
       c.status                                    AS company_status,

       -- Manager ratings
       COALESCE(mr.visible,  0)                    AS mgr_visible,
       COALESCE(mr.held,     0)                    AS mgr_held,
       COALESCE(mr.rejected, 0)                    AS mgr_rejected,
       COALESCE(mr.deleted,  0)                    AS mgr_deleted,
       COALESCE(mr.managers_rated, 0)              AS managers_with_a_visible_rating,

       -- Company ratings. `held` is NULL, not 0: the concept does not exist for these.
       COALESCE(cy.visible, 0)                     AS co_visible,
       NULL::bigint                                AS co_held,
       COALESCE(cy.deleted, 0)                     AS co_deleted,

       -- Convenience only. Deliberately NOT a sum of the two kinds, which would be meaningless;
       -- this is "does this company have any publicly visible rating of either kind".
       (COALESCE(mr.visible, 0) > 0
        OR COALESCE(cy.visible, 0) > 0)            AS has_any_visible_rating,

       -- GREATEST ignores NULLs, so this is simply the later of the two when both exist and the
       -- non-null one otherwise. (FILTER cannot be used here: it applies only to aggregates.)
       GREATEST(mr.last_rated_at, cy.last_rated_at) AS last_rated_at
  FROM companies c
  LEFT JOIN manager_ratings mr ON mr.company_id = c.id
  LEFT JOIN company_ratings cy ON cy.company_id = c.id
 ORDER BY COALESCE(mr.visible, 0) + COALESCE(cy.visible, 0) DESC, c.name;

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. Totals, to sanity-check the per-company table against
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Run this second and compare. If the column sums above do not match these, the join dropped rows
-- - most likely manager ratings whose manager has no company_id, which the per-company table
-- cannot show and which are counted on their own line here.
SELECT 'manager ratings'                                            AS kind,
       count(*) FILTER (WHERE r.disposition = 'live'
                          AND r.deleted_at IS NULL)                 AS visible,
       count(*) FILTER (WHERE r.disposition = 'held'
                          AND r.deleted_at IS NULL)                 AS held,
       count(*) FILTER (WHERE r.disposition = 'rejected'
                          AND r.deleted_at IS NULL)                 AS rejected,
       count(*) FILTER (WHERE r.deleted_at IS NOT NULL)             AS deleted,
       count(*)                                                     AS total_rows
  FROM reviews r
UNION ALL
SELECT 'manager ratings with no company',
       count(*) FILTER (WHERE r.disposition = 'live' AND r.deleted_at IS NULL),
       count(*) FILTER (WHERE r.disposition = 'held' AND r.deleted_at IS NULL),
       count(*) FILTER (WHERE r.disposition = 'rejected' AND r.deleted_at IS NULL),
       count(*) FILTER (WHERE r.deleted_at IS NOT NULL),
       count(*)
  FROM reviews r
  JOIN managers m ON m.id = r.manager_id
 WHERE m.company_id IS NULL
UNION ALL
SELECT 'company ratings',
       count(*) FILTER (WHERE cr.deleted_at IS NULL),
       NULL::bigint,                                 -- no held concept, see the header
       NULL::bigint,
       count(*) FILTER (WHERE cr.deleted_at IS NOT NULL),
       count(*)
  FROM company_reviews cr;
