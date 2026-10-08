-- ============================================================================
-- company_stats_live: the database becomes the only writer
-- ============================================================================
--
-- WHY
--
-- Until now this projection had three maintainers for the same columns: the V47 trigger on
-- managers, around fifty awaited Java call sites across two backends, and a periodic full
-- refresh. CLAUDE.md section 21 asks for exactly one, and the reason is not tidiness.
--
-- Werkpages and RateMyManagers are two backends on one database. A Java maintainer in one of them
-- cannot react to a write made by the other, and neither can react to a migration, a backfill or
-- a hand-run UPDATE. The only component both backends share is this database, so the database is
-- the only place that CAN be the single writer. Every application-side answer is structurally
-- incapable of it, however carefully written.
--
-- The consequence of not having one was live until today: RateMyManagers wrote six of the nine
-- columns and left workplace_count untouched, so a workplace rating submitted there was missing
-- from the table Werkpages reads until the next periodic refresh, up to six hours later.
--
-- WHAT THIS CHANGES
--
--   1. refresh_company_stats() writes all nine columns, not six. It previously maintained only
--      the manager columns, which is why the workplace and interview columns needed Java at all.
--
--   2. The managers join becomes a LEFT JOIN, so a company with ratings but no managers still
--      gets a row. With an inner join such a company could never be projected.
--
--   3. The orphan DELETE now requires that nothing at all remains - no live manager, no live
--      workplace rating, no live interview experience. It previously deleted any row without a
--      qualifying manager, which would have discarded the workplace counts this function is now
--      responsible for, and would have made the reconciler report drift for every managerless
--      company that had been rated.
--
--   4. Three new triggers cover the three tables that feed the projection and had none:
--      companies.logo_url, company_reviews and interview_reviews.
--
-- WHAT THIS DOES NOT CHANGE
--
--   * The visibility rules. Managers still count only when approval_status is 'approved' or
--     'ghost' and the row is not a seed_ placeholder, exactly as before. findCompanyListing still
--     filters manager_count > 0, so a managerless company stays off the public Companies tab
--     (CLAUDE.md section 22) even though it now keeps a row.
--   * company_interview_stats, which has its own trigger and its own purpose. The existing
--     interview_reviews_stats trigger is left alone; the one added here is a second, separate
--     trigger for this projection.
--
-- ORDERING
--
-- This migration must be applied BEFORE either backend's Java stats writers are removed. While
-- both exist they simply agree, which is why this is safe to apply on its own.
-- ============================================================================


-- ── 1. The function, now writing every column it owns ───────────────────────

CREATE OR REPLACE FUNCTION refresh_company_stats(p_company_id BIGINT)
RETURNS VOID AS $$
BEGIN
    IF p_company_id IS NULL THEN
        RETURN;
    END IF;

    -- Deleting a company cascades into managers, company_reviews and interview_reviews, each of
    -- which fires a trigger below while the company row is already gone. Without this guard the
    -- refresh would try to INSERT a stats row referencing a company that no longer exists and
    -- fail on its own foreign key, taking the company deletion down with it. The cascade on
    -- company_stats_live removes the row.
    IF NOT EXISTS (SELECT 1 FROM companies WHERE id = p_company_id) THEN
        RETURN;
    END IF;

    INSERT INTO company_stats_live (
        company_id, manager_count, total_reviews, avg_rating, logo_url,
        workplace_count, workplace_avg_rating,
        interview_count, interview_avg_rating, updated_at)
    SELECT c.id,
           COUNT(DISTINCT m.id),
           COALESCE(SUM(m.reviews_count), 0),
           ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
                 AND m.reviews_count > 0)::NUMERIC, 1),
           COALESCE(MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
                    c.logo_url,
                    MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
           -- The other two datasets, as correlated subqueries rather than joins. A join to
           -- company_reviews and interview_reviews alongside the managers join would multiply the
           -- rows out, and every COUNT and AVG above it would silently inflate. Correlated
           -- scalars cannot fan out.
           (SELECT COUNT(*) FROM company_reviews cr
             WHERE cr.company_id = c.id AND cr.deleted_at IS NULL),
           (SELECT ROUND(AVG(cr.overall_rating)::NUMERIC, 1) FROM company_reviews cr
             WHERE cr.company_id = c.id AND cr.deleted_at IS NULL),
           (SELECT COUNT(*) FROM interview_reviews ir
             WHERE ir.company_id = c.id AND ir.deleted_at IS NULL),
           (SELECT ROUND(AVG(ir.overall_rating)::NUMERIC, 1) FROM interview_reviews ir
             WHERE ir.company_id = c.id AND ir.deleted_at IS NULL),
           now()
    FROM companies c
    -- LEFT, so a company with ratings but no managers still produces a row. The approval and
    -- seed predicates move into the ON clause for the same reason: in a WHERE clause they would
    -- turn this back into an inner join.
    LEFT JOIN managers m ON m.company_id = c.id
         AND m.approval_status IN ('approved', 'ghost')
         AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
    WHERE c.id = p_company_id
    GROUP BY c.id, c.logo_url
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

    -- The orphan case: a row with nothing left behind it.
    --
    -- Narrower than it used to be. The old predicate removed any row without a qualifying
    -- manager, which was right while this function only maintained manager columns and wrong the
    -- moment it took over the workplace and interview counts: it would have thrown away the
    -- ratings of every company whose managers had gone, and the reconciler would then have
    -- reported drift it could not explain.
    DELETE FROM company_stats_live cs
    WHERE cs.company_id = p_company_id
      AND NOT EXISTS (
          SELECT 1 FROM managers m
           WHERE m.company_id = p_company_id
             AND m.approval_status IN ('approved', 'ghost')
             AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%'))
      AND NOT EXISTS (
          SELECT 1 FROM company_reviews cr
           WHERE cr.company_id = p_company_id AND cr.deleted_at IS NULL)
      AND NOT EXISTS (
          SELECT 1 FROM interview_reviews ir
           WHERE ir.company_id = p_company_id AND ir.deleted_at IS NULL);
END;
$$ LANGUAGE plpgsql;


-- ── 2. companies.logo_url ───────────────────────────────────────────────────
--
-- c.logo_url feeds the projected logo through the COALESCE above, and nothing watched it. The
-- only thing that ever repaired a logo change was the periodic full refresh, so a logo set
-- through a career-entry path was wrong on the Companies tab for up to six hours.

CREATE OR REPLACE FUNCTION companies_company_stats_trigger()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM refresh_company_stats(NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS companies_company_stats ON companies;
CREATE TRIGGER companies_company_stats
AFTER UPDATE OF logo_url ON companies
FOR EACH ROW EXECUTE FUNCTION companies_company_stats_trigger();


-- ── 3. company_reviews ─────────────────────────────────────────────────────
--
-- workplace_count and workplace_avg_rating had no trigger at all and were maintained only by
-- CompanyReviewService in each backend. That is what let RateMyManagers drift.

CREATE OR REPLACE FUNCTION company_reviews_company_stats_trigger()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM refresh_company_stats(OLD.company_id);
        RETURN OLD;
    END IF;

    -- A rating moved between companies leaves the old one stale unless it is recomputed too.
    IF TG_OP = 'UPDATE' AND OLD.company_id IS DISTINCT FROM NEW.company_id THEN
        PERFORM refresh_company_stats(OLD.company_id);
    END IF;

    PERFORM refresh_company_stats(NEW.company_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS company_reviews_company_stats ON company_reviews;
CREATE TRIGGER company_reviews_company_stats
AFTER INSERT OR DELETE OR UPDATE OF company_id, overall_rating, deleted_at ON company_reviews
FOR EACH ROW EXECUTE FUNCTION company_reviews_company_stats_trigger();


-- ── 4. interview_reviews ───────────────────────────────────────────────────
--
-- interview_reviews already carries a trigger, interview_reviews_stats, but it maintains the
-- separate company_interview_stats table and never touched this projection. Reading the trigger
-- list and concluding the interview columns were covered is a mistake that has been made.
-- This is a second, separate trigger; the existing one is left exactly as it is.

CREATE OR REPLACE FUNCTION interview_reviews_company_stats_trigger()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM refresh_company_stats(OLD.company_id);
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.company_id IS DISTINCT FROM NEW.company_id THEN
        PERFORM refresh_company_stats(OLD.company_id);
    END IF;

    PERFORM refresh_company_stats(NEW.company_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS interview_reviews_company_stats ON interview_reviews;
CREATE TRIGGER interview_reviews_company_stats
AFTER INSERT OR DELETE OR UPDATE OF company_id, overall_rating, deleted_at ON interview_reviews
FOR EACH ROW EXECUTE FUNCTION interview_reviews_company_stats_trigger();


-- ── 5. Bring every existing row in line with the new definition ────────────
--
-- The function above only runs when something changes. Without this, a company that is quiet
-- from today keeps whatever the old six-column maintenance left it with, and the reconciler
-- would report that as drift on the first run after deployment.

INSERT INTO company_stats_live (
    company_id, manager_count, total_reviews, avg_rating, logo_url,
    workplace_count, workplace_avg_rating,
    interview_count, interview_avg_rating, updated_at)
SELECT c.id,
       COUNT(DISTINCT m.id),
       COALESCE(SUM(m.reviews_count), 0),
       ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
             AND m.reviews_count > 0)::NUMERIC, 1),
       COALESCE(MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
                c.logo_url,
                MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
       (SELECT COUNT(*) FROM company_reviews cr
         WHERE cr.company_id = c.id AND cr.deleted_at IS NULL),
       (SELECT ROUND(AVG(cr.overall_rating)::NUMERIC, 1) FROM company_reviews cr
         WHERE cr.company_id = c.id AND cr.deleted_at IS NULL),
       (SELECT COUNT(*) FROM interview_reviews ir
         WHERE ir.company_id = c.id AND ir.deleted_at IS NULL),
       (SELECT ROUND(AVG(ir.overall_rating)::NUMERIC, 1) FROM interview_reviews ir
         WHERE ir.company_id = c.id AND ir.deleted_at IS NULL),
       now()
FROM companies c
LEFT JOIN managers m ON m.company_id = c.id
     AND m.approval_status IN ('approved', 'ghost')
     AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
GROUP BY c.id, c.logo_url
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

-- And remove rows that the new, narrower orphan rule says should not exist.
DELETE FROM company_stats_live cs
WHERE NOT EXISTS (
      SELECT 1 FROM managers m
       WHERE m.company_id = cs.company_id
         AND m.approval_status IN ('approved', 'ghost')
         AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%'))
  AND NOT EXISTS (
      SELECT 1 FROM company_reviews cr
       WHERE cr.company_id = cs.company_id AND cr.deleted_at IS NULL)
  AND NOT EXISTS (
      SELECT 1 FROM interview_reviews ir
       WHERE ir.company_id = cs.company_id AND ir.deleted_at IS NULL);
