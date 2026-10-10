-- company_stats_live gets ONE writer: this database.
--
-- V47 put the manager aggregates behind a trigger and deliberately left the application refresh
-- in place, calling it "idempotent and now redundant, but harmless". V79 then added four more
-- columns - workplace_count, workplace_avg_rating, interview_count, interview_avg_rating - and
-- taught only the application how to fill them. The trigger function was never updated.
--
-- So the projection has had two writers with different coverage: the trigger maintains five
-- columns, and the other four exist only if some Java call path remembers to ask. Two products
-- share this database and each would have to remember forever, which is the arrangement V47's own
-- comment set out to end.
--
-- It is also not merely a tidiness problem. Every workplace and interview number a company tile
-- shows depends on an application call succeeding, and those calls recover from failure by
-- logging and carrying on - by design, so a stats problem cannot fail somebody's rating. The
-- failure mode is therefore silent staleness in the numbers readers actually see.
--
-- CLAUDE.md section 21 permits a read table only with transactional maintenance from known
-- mutations, a rebuild, and a reconciliation. This migration is the first of those: the maintenance
-- moves wholly into the database, where it happens inside the same statement as the mutation and
-- cannot be forgotten by a caller.

-- ── Single-company recompute, now covering every column ───────────────────────
--
-- Two changes from V47 beyond the extra columns.
--
-- LEFT JOIN managers, not JOIN. An inner join returns no rows for a company with no qualifying
-- managers, so the upsert wrote nothing and a company rated as a WORKPLACE but with no managers
-- listed could never get a row at all. Its filters move into the join condition so they narrow
-- the managers side rather than discarding the company.
--
-- The other two datasets are correlated subqueries rather than joins. Joining them alongside
-- managers multiplies the rows out, and every COUNT and AVG above would silently inflate.
-- Correlated scalars cannot fan out. (Carried over verbatim from the application query this
-- replaces, which had already learned that.)
CREATE OR REPLACE FUNCTION refresh_company_stats(p_company_id BIGINT) RETURNS void AS $$
BEGIN
    IF p_company_id IS NULL THEN
        RETURN;
    END IF;

    INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url,
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

    -- The orphan case, narrowed.
    --
    -- V47 deleted the row whenever no qualifying MANAGERS remained, which was correct when
    -- managers were the only dataset. It is now destructive: a company with two workplace ratings
    -- and no managers would have its row - and those ratings' counts - deleted on the next
    -- recompute. A row is only an orphan when the company has nothing in any dataset.
    DELETE FROM company_stats_live cs
    WHERE cs.company_id = p_company_id
      AND cs.manager_count   = 0
      AND cs.workplace_count = 0
      AND cs.interview_count = 0;
END;
$$ LANGUAGE plpgsql;

-- ── Triggers for the datasets the function now reads ──────────────────────────
--
-- One function for both review tables: they are the same shape for this purpose, each carrying a
-- company_id that can be moved by an UPDATE. Writing it twice is how the two would drift.
CREATE OR REPLACE FUNCTION company_dataset_company_stats_trigger() RETURNS trigger AS $$
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

-- UPDATE OF <columns> keeps the trigger off the hot path for edits that cannot move these
-- aggregates. deleted_at is in the list because a withdrawal is an UPDATE, not a DELETE, and it
-- changes both the count and the average.
CREATE TRIGGER company_reviews_company_stats
AFTER INSERT OR DELETE OR UPDATE OF company_id, overall_rating, deleted_at
ON company_reviews
FOR EACH ROW EXECUTE FUNCTION company_dataset_company_stats_trigger();

DROP TRIGGER IF EXISTS interview_reviews_company_stats ON interview_reviews;

CREATE TRIGGER interview_reviews_company_stats
AFTER INSERT OR DELETE OR UPDATE OF company_id, overall_rating, deleted_at
ON interview_reviews
FOR EACH ROW EXECUTE FUNCTION company_dataset_company_stats_trigger();

-- ── The company's own row ─────────────────────────────────────────────────────
--
-- logo_url is read by the function as the fallback when no manager carries one, so changing a
-- company's logo has to recompute its projection. Nothing else on `companies` feeds these numbers.
CREATE OR REPLACE FUNCTION companies_company_stats_trigger() RETURNS trigger AS $$
BEGIN
    PERFORM refresh_company_stats(NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS companies_company_stats ON companies;

CREATE TRIGGER companies_company_stats
AFTER UPDATE OF logo_url
ON companies
FOR EACH ROW EXECUTE FUNCTION companies_company_stats_trigger();

-- ── Backfill: every company with anything in any dataset ──────────────────────
--
-- The four columns V79 added have only ever been written by application code, so any company
-- whose last workplace or interview rating was recorded by a path that did not call it is wrong
-- now. Recomputing through the function keeps one definition of these numbers rather than
-- repeating the query here, which is how V47's backfill and its function drifted apart.
DO $$
DECLARE
    cid BIGINT;
BEGIN
    FOR cid IN
        SELECT DISTINCT company_id FROM (
            SELECT company_id FROM managers          WHERE company_id IS NOT NULL
            UNION ALL
            SELECT company_id FROM company_reviews   WHERE company_id IS NOT NULL
            UNION ALL
            SELECT company_id FROM interview_reviews WHERE company_id IS NOT NULL
            UNION ALL
            SELECT company_id FROM company_stats_live
        ) AS all_companies
    LOOP
        PERFORM refresh_company_stats(cid);
    END LOOP;
END;
$$;
