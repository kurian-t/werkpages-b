-- Real table for near-real-time company stats, updated on each mutation.
-- Runs alongside the company_stats matview so both can be compared before
-- the matview is dropped. findCompanyListing() reads from this table.
CREATE TABLE company_stats_live (
    company_id    BIGINT PRIMARY KEY REFERENCES companies(id) ON DELETE CASCADE,
    manager_count BIGINT        NOT NULL DEFAULT 0,
    total_reviews BIGINT        NOT NULL DEFAULT 0,
    avg_rating    NUMERIC(3, 1),
    logo_url      TEXT,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Backfill from the freshly-rebuilt matview.
INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
SELECT company_id, manager_count, total_reviews, avg_rating, logo_url, now()
FROM company_stats
ON CONFLICT (company_id) DO NOTHING;
