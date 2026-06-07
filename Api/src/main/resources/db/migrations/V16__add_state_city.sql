-- Geo refinement: state/province and city for managers (inferred from Cloudflare
-- visitor-location headers on creation). Both nullable; existing rows stay valid.
ALTER TABLE managers ADD COLUMN IF NOT EXISTS state VARCHAR(100);
ALTER TABLE managers ADD COLUMN IF NOT EXISTS city  VARCHAR(100);
