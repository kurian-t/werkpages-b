-- Add company_id FK to career_history so company profile queries use a join
-- rather than fragile text matching.
ALTER TABLE career_history ADD COLUMN company_id BIGINT REFERENCES companies(id);

-- Backfill where names match exactly (case-insensitive).
UPDATE career_history ch
SET company_id = c.id
FROM companies c
WHERE LOWER(TRIM(ch.company)) = LOWER(TRIM(c.name));

CREATE INDEX career_history_company_id_idx ON career_history(company_id);
