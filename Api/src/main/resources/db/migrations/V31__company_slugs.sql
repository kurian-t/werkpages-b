ALTER TABLE companies ADD COLUMN slug TEXT;

-- Backfill base slugs from name
UPDATE companies
SET slug = lower(regexp_replace(
    regexp_replace(lower(trim(name)), '[^a-z0-9\s-]', '', 'g'),
    '\s+', '-', 'g'
));

-- Resolve any conflicts (unlikely since company names are already unique, but safe)
WITH conflicts AS (
    SELECT id,
        slug,
        ROW_NUMBER() OVER (PARTITION BY slug ORDER BY id) AS rn
    FROM companies
)
UPDATE companies c
SET slug = c.slug || '-' || cf.rn
FROM conflicts cf
WHERE c.id = cf.id AND cf.rn > 1;

-- Fallback for any empty slugs
UPDATE companies SET slug = 'company-' || id WHERE slug IS NULL OR slug = '';

ALTER TABLE companies ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX companies_slug_idx ON companies(slug);

-- Auto-generate slug when not provided (guards test inserts and any future direct SQL)
CREATE OR REPLACE FUNCTION companies_auto_slug()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.slug IS NULL THEN
        NEW.slug := lower(regexp_replace(
            regexp_replace(lower(trim(NEW.name)), '[^a-z0-9\s-]', '', 'g'),
            '\s+', '-', 'g'
        )) || '-' || NEW.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER companies_auto_slug_trg
BEFORE INSERT ON companies
FOR EACH ROW EXECUTE FUNCTION companies_auto_slug();
