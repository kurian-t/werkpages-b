-- Industry classification for companies.
-- Populated by AI (Claude) classification into a fixed taxonomy: a one-time bulk
-- backfill (POST /api/admin/industries/classify) plus fire-and-forget classification
-- of each brand-new company as it is created via CompanyRepository.findOrCreate.
-- Nullable: a company stays NULL until it has been classified.
ALTER TABLE companies ADD COLUMN IF NOT EXISTS industry TEXT;

-- Partial index: industry lookups/aggregations only ever care about classified rows.
CREATE INDEX IF NOT EXISTS companies_industry_idx ON companies (industry) WHERE industry IS NOT NULL;
