-- Re-run career_history company_id backfill.
-- V18 only set company_id where the company already existed in the companies table
-- at migration time. Companies created later (e.g. via company profile page visits)
-- were missed. This fills in the remaining NULLs.
UPDATE career_history ch
SET company_id = c.id
FROM companies c
WHERE ch.company_id IS NULL
  AND LOWER(TRIM(ch.company)) = LOWER(TRIM(c.name));
