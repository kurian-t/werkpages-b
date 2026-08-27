-- company_stats_live could never be zeroed out.
--
-- The application refresh (CompanyRepository.updateCompanyStatsForManager) is an
-- INSERT ... SELECT ... ON CONFLICT DO UPDATE whose SELECT joins `managers`. When a company
-- loses its last qualifying manager the SELECT returns no rows, so nothing is inserted, the
-- ON CONFLICT branch never runs, and the previous row survives untouched — forever.
--
-- Result: companies with zero managers kept advertising stale manager/review counts, and
-- because findCompaniesByIndustry filters on `cs.manager_count > 0` they stayed visible in
-- listings. The industry header (which recomputes live) disagreed with the company cards
-- (which read this cache) — e.g. "0 reviews" beside a card claiming 1 review and a 4.1 rating.
--
-- Two fixes here:
--   1. refresh_company_stats() — recompute for one company, and DELETE the row when no
--      qualifying managers remain. This is the case the app-side refresh cannot express.
--   2. A trigger on `managers` so the cache is maintained by the database rather than by
--      whichever application happened to perform the write. RateMyManagers and Werkpages share
--      this database and each maintain these counters in their own code; a trigger removes the
--      requirement that both forks stay in sync forever.
--
-- The app-side refresh is left in place. It is idempotent and now redundant, but harmless.

-- ── Single-company recompute ──────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION refresh_company_stats(p_company_id BIGINT) RETURNS void AS $$
BEGIN
    IF p_company_id IS NULL THEN
        RETURN;
    END IF;

    INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
    SELECT c.id,
           COUNT(DISTINCT m.id),
           COALESCE(SUM(m.reviews_count), 0),
           ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
                 AND m.reviews_count > 0)::NUMERIC, 1),
           COALESCE(MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
                    c.logo_url,
                    MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
           now()
    FROM companies c
    JOIN managers m ON m.company_id = c.id
    WHERE c.id = p_company_id
      AND m.approval_status IN ('approved', 'ghost')
      AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
    GROUP BY c.id, c.logo_url
    ON CONFLICT (company_id) DO UPDATE SET
        manager_count = EXCLUDED.manager_count,
        total_reviews = EXCLUDED.total_reviews,
        avg_rating    = EXCLUDED.avg_rating,
        logo_url      = EXCLUDED.logo_url,
        updated_at    = now();

    -- The statement above writes nothing when a company has no qualifying managers, so a
    -- previously cached row would otherwise persist. This is the orphan case.
    DELETE FROM company_stats_live cs
    WHERE cs.company_id = p_company_id
      AND NOT EXISTS (
          SELECT 1 FROM managers m
          WHERE m.company_id = p_company_id
            AND m.approval_status IN ('approved', 'ghost')
            AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
      );
END;
$$ LANGUAGE plpgsql;

-- ── Trigger ───────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION managers_company_stats_trigger() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM refresh_company_stats(OLD.company_id);
        RETURN OLD;
    END IF;

    -- A manager moving between companies leaves the old company stale unless it is
    -- recomputed too.
    IF TG_OP = 'UPDATE' AND OLD.company_id IS DISTINCT FROM NEW.company_id THEN
        PERFORM refresh_company_stats(OLD.company_id);
    END IF;

    PERFORM refresh_company_stats(NEW.company_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS managers_company_stats ON managers;

-- UPDATE OF <columns> keeps the trigger off the hot path for edits that cannot affect these
-- aggregates (name, title, bio, slug, timestamps).
CREATE TRIGGER managers_company_stats
AFTER INSERT OR DELETE OR UPDATE OF
    company_id, approval_status, external_id, reviews_count, overall_rating, company_logo_url
ON managers
FOR EACH ROW EXECUTE FUNCTION managers_company_stats_trigger();

-- ── One-time repair of existing drift ─────────────────────────────────────────
DELETE FROM company_stats_live cs
WHERE NOT EXISTS (
    SELECT 1 FROM managers m
    WHERE m.company_id = cs.company_id
      AND m.approval_status IN ('approved', 'ghost')
      AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
);

INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
SELECT c.id,
       COUNT(DISTINCT m.id),
       COALESCE(SUM(m.reviews_count), 0),
       ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
             AND m.reviews_count > 0)::NUMERIC, 1),
       COALESCE(MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
                c.logo_url,
                MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
       now()
FROM companies c
JOIN managers m ON m.company_id = c.id
WHERE m.approval_status IN ('approved', 'ghost')
  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
GROUP BY c.id, c.logo_url
ON CONFLICT (company_id) DO UPDATE SET
    manager_count = EXCLUDED.manager_count,
    total_reviews = EXCLUDED.total_reviews,
    avg_rating    = EXCLUDED.avg_rating,
    logo_url      = EXCLUDED.logo_url,
    updated_at    = now();
