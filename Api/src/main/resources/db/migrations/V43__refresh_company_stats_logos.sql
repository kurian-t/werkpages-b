-- Refresh company_stats_live.logo_url for every company.
-- This backfills rows that became stale because doUpdate and updateManagerLogo
-- previously did not trigger a company_stats_live refresh after changing a
-- manager's company_logo_url.
INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
SELECT c.id,
       COUNT(DISTINCT m.id),
       COALESCE(SUM(m.reviews_count), 0),
       ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0)::NUMERIC, 1),
       COALESCE(
           MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
           c.logo_url,
           MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)
       ),
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
