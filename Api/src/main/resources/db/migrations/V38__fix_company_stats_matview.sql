-- Replace the slow company_stats matview with a fast FK-only join.
-- The original JOIN used OR EXISTS subqueries across career_history and reviews,
-- causing 10-minute refresh times. All approved/ghost managers now have company_id
-- set via the companies table (enforced since V17), so the FK join is sufficient.

DROP MATERIALIZED VIEW IF EXISTS company_stats CASCADE;

CREATE MATERIALIZED VIEW company_stats AS
SELECT
    c.id                                                                               AS company_id,
    COUNT(DISTINCT m.id)                                                               AS manager_count,
    COALESCE(SUM(m.reviews_count), 0)                                                  AS total_reviews,
    ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
          AND m.reviews_count > 0)::NUMERIC, 1)                                        AS avg_rating,
    COALESCE(
        MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
        c.logo_url,
        MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)
    )                                                                                   AS logo_url
FROM companies c
JOIN managers m ON m.company_id = c.id
WHERE m.approval_status IN ('approved', 'ghost')
  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
GROUP BY c.id, c.logo_url
WITH DATA;

CREATE UNIQUE INDEX company_stats_company_id_idx ON company_stats(company_id);
