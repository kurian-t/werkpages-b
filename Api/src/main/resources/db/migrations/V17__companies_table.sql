-- First-class companies table. Analytics (manager count, avg rating, etc.) are
-- calculated on the fly by JOINing companies → managers. No cached columns here.
-- See CLAUDE.md for the materialized-view plan when query performance needs it.

CREATE TABLE companies (
    id         BIGSERIAL    PRIMARY KEY,
    name       TEXT         NOT NULL,
    domain     TEXT,
    logo_url   TEXT,
    status     TEXT         NOT NULL DEFAULT 'ghost',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Case-insensitive unique index on trimmed name.
-- ON CONFLICT target must use double-parens: ON CONFLICT ((LOWER(TRIM(name))))
CREATE UNIQUE INDEX companies_name_ci ON companies (LOWER(TRIM(name)));

-- Backfill one company row per unique (case-insensitive) manager company name.
-- MIN(company) picks one canonical casing; MIN(company_logo_url) carries forward
-- any logo already stored on the manager row.
INSERT INTO companies (name, logo_url, status, created_at, updated_at)
SELECT MIN(company), MIN(company_logo_url), 'approved', MIN(created_at), now()
FROM managers
WHERE approval_status IN ('approved', 'ghost')
  AND company IS NOT NULL AND TRIM(company) != ''
GROUP BY LOWER(TRIM(company))
ON CONFLICT ((LOWER(TRIM(name)))) DO NOTHING;

-- Add company_id FK to managers. Nullable so pending/rejected managers without
-- a matching company row remain valid.
ALTER TABLE managers ADD COLUMN company_id BIGINT REFERENCES companies(id);

-- Backfill company_id on all existing managers whose company name resolves.
UPDATE managers m
SET company_id = c.id
FROM companies c
WHERE LOWER(TRIM(m.company)) = LOWER(TRIM(c.name))
  AND m.company IS NOT NULL AND TRIM(m.company) != '';

-- Index for FK lookups (JOIN companies → managers).
CREATE INDEX managers_company_id_idx ON managers (company_id);
