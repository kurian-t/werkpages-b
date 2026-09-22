-- Gives managers back the plain slug for their name, where a collision-breaker outlived the
-- collision that caused it.
--
-- generateUniqueSlug appends the company when the plain name slug is already taken, so a second
-- "Poonam Yadav" became poonam-yadav-aditya-birla-group. The slug is decided once, at INSERT, and
-- nothing ever recomputed it - so merging the two, which is exactly what frees the plain slug,
-- left the survivor wearing the collision-breaker for good. ManagerRepository.reclaimBaseSlug now
-- handles that at merge time; this is the same repair for the rows already out there.
--
-- ── What this does NOT do ───────────────────────────────────────────────────────────────────
--
-- It does not touch a manager whose plain slug is held by anybody still visible. Two different
-- people really can share a name, and the second one is entitled to its suffix. Only slugs held
-- by rows that are merged away or rejected - invisible on every public surface, which all filter
-- approval_status to 'approved' and 'ghost' - are treated as free.
--
-- ── Links keep working ──────────────────────────────────────────────────────────────────────
--
-- Every slug this changes is written to manager_url_history first, with the company slug it was
-- published under. Both backends read that table when a slug does not resolve directly, so shared
-- links and anything a search engine indexed 301 to the new URL instead of 404ing. `managers` is
-- shared between Werkpages and RateMyManagers, and RMM reads and writes that same history table,
-- so both sites keep resolving these URLs.

-- 1. The plain slug each manager would get from its name today, using the same transformation
--    toBaseSlug applies in Java: lowercase, strip anything but letters/digits/space/hyphen,
--    collapse whitespace to single hyphens.
CREATE TEMP TABLE slug_repair ON COMMIT DROP AS
WITH base AS (
    SELECT m.id,
           m.slug        AS current_slug,
           c.slug        AS company_slug,
           regexp_replace(
               regexp_replace(lower(trim(m.name)), '[^a-z0-9[:space:]-]', '', 'g'),
               '[[:space:]]+', '-', 'g') AS base_slug
    FROM managers m
    LEFT JOIN companies c ON c.id = m.company_id
    -- Only live rows are worth repairing; a retired row's slug is not a URL anybody should reach.
    WHERE m.approval_status IN ('approved', 'ghost')
)
SELECT b.id, b.current_slug, b.company_slug, b.base_slug
FROM base b
WHERE b.base_slug <> ''
  AND b.current_slug <> b.base_slug
  -- Free, or held only by something invisible.
  AND NOT EXISTS (
        SELECT 1 FROM managers h
         WHERE h.slug = b.base_slug
           AND h.id <> b.id
           AND h.approval_status IN ('approved', 'ghost')
      );

-- 2. One winner per freed slug. Two live managers can both want "sam-rivera"; the older row takes
--    it and the other keeps what it has, which is the same order generateUniqueSlug would have
--    produced had they been inserted in sequence.
DELETE FROM slug_repair r
 USING slug_repair other
 WHERE r.base_slug = other.base_slug
   AND r.id > other.id;

-- 3. Park the slug on any invisible row still holding it, so the UPDATE below cannot collide with
--    the unique index on managers(slug).
UPDATE managers h
   SET slug = h.slug || '-merged-' || h.id, updated_at = now()
  FROM slug_repair r
 WHERE h.slug = r.base_slug
   AND h.id <> r.id;

-- 4. Record where each manager used to live BEFORE moving it. A manager with no company has no
--    published URL of this shape, so there is nothing to preserve for it.
INSERT INTO manager_url_history (manager_id, company_slug, manager_slug)
SELECT r.id, r.company_slug, r.current_slug
FROM slug_repair r
WHERE r.company_slug IS NOT NULL
ON CONFLICT DO NOTHING;

-- 5. Move them.
UPDATE managers m
   SET slug = r.base_slug, updated_at = now()
  FROM slug_repair r
 WHERE m.id = r.id;
