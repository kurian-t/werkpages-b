-- Recreate company_stats prioritising logo.dev manager URLs over the companies.logo_url column.
-- Manager cards store the correct real domain (e.g. stchas.edu) when the company was chosen
-- via autocomplete; companies.logo_url was set on first visit using a guessed Clearbit domain
-- which is wrong for many companies. Prefer the exact domain from managers when available.
DROP MATERIALIZED VIEW IF EXISTS company_stats;

CREATE MATERIALIZED VIEW company_stats AS
SELECT
    c.id                                                                              AS company_id,
    COUNT(DISTINCT m.id)                                                              AS manager_count,
    COALESCE(SUM(m.reviews_count), 0)                                                 AS total_reviews,
    ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL
          AND m.reviews_count > 0)::NUMERIC, 1)                                       AS avg_rating,
    COALESCE(
        MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
        c.logo_url,
        MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)
    )                                                                                  AS logo_url
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

CREATE UNIQUE INDEX company_stats_company_id_idx ON company_stats(company_id);
