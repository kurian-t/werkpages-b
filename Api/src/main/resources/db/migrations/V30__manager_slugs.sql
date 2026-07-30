ALTER TABLE managers ADD COLUMN slug TEXT;

-- Backfill: generate base slug from name, then deduplicate with sequential suffix
WITH base AS (
    SELECT id,
        lower(regexp_replace(
            regexp_replace(lower(trim(name)), '[^a-z0-9\s-]', '', 'g'),
            '\s+', '-', 'g'
        )) AS base_slug
    FROM managers
),
ranked AS (
    SELECT id, base_slug,
        ROW_NUMBER() OVER (PARTITION BY base_slug ORDER BY id) AS rn
    FROM base
)
UPDATE managers m
SET slug = CASE
    WHEN r.rn = 1 THEN r.base_slug
    ELSE r.base_slug || '-' || r.rn
END
FROM ranked r
WHERE m.id = r.id;

-- Fallback for any empty slugs (e.g. names that were all special characters)
UPDATE managers SET slug = 'manager-' || id WHERE slug IS NULL OR slug = '';

ALTER TABLE managers ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX managers_slug_idx ON managers(slug);

-- Auto-generate slug when not provided (guards test inserts and any future direct SQL)
CREATE OR REPLACE FUNCTION managers_auto_slug()
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

CREATE TRIGGER managers_auto_slug_trg
BEFORE INSERT ON managers
FOR EACH ROW EXECUTE FUNCTION managers_auto_slug();
