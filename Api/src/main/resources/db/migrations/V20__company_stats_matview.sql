-- Materialized view for company listing stats.
-- The complex multi-source JOIN (company_id FK + career_history + reviews) is expensive
-- to run on every page load across all companies. Pre-computing it here makes the
-- listing query a trivial indexed scan. Refresh after mutations via
-- REFRESH MATERIALIZED VIEW CONCURRENTLY company_stats.
CREATE MATERIALIZED VIEW company_stats AS
SELECT
    c.id                                                                              AS company_id,
    COUNT(DISTINCT m.id)                                                              AS manager_count,
    COALESCE(SUM(m.reviews_count), 0)                                                 AS total_reviews,
    ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
          AND m.reviews_count > 0)::NUMERIC, 1)                                       AS avg_rating,
    COALESCE(c.logo_url,
             MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL))   AS logo_url
FROM companies c
JOIN managers m ON (
    m.company_id = c.id
    OR EXISTS (
        SELECT 1 FROM career_history ch
        WHERE ch.manager_id = m.id
          AND (ch.company_id = c.id
               OR (ch.company_id IS NULL
                   AND LOWER(TRIM(ch.company)) = LOWER(TRIM(c.name))))
    )
    OR EXISTS (
        SELECT 1 FROM reviews r
        WHERE r.manager_id = m.id
          AND LOWER(TRIM(r.manager_company)) = LOWER(TRIM(c.name))
    )
)
WHERE m.approval_status IN ('approved', 'ghost')
  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
GROUP BY c.id, c.logo_url
WITH DATA;

-- Required for REFRESH MATERIALIZED VIEW CONCURRENTLY (non-locking refresh).
CREATE UNIQUE INDEX company_stats_company_id_idx ON company_stats(company_id);
