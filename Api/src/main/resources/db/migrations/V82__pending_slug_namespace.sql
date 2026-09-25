-- Moves existing pending managers into the reserved "-pending" slug namespace.
--
-- Slug allocation asks "SELECT 1 FROM managers WHERE slug = $1": every row blocks a name,
-- including rows no visitor can ever open. So a manager half-typed into the add form and never
-- submitted took "sourabh-setia", and the real manager published thirteen seconds later was
-- pushed onto "sourabh-setia-lumenwerx". That mangled slug was not a naming decision; it was a
-- collision with something invisible.
--
-- The application now allocates pending slugs inside "-pending", so this cannot recur. This is the
-- one-time pass over what already exists, and the point of it is the SECOND statement: freeing the
-- clean names lets the live managers stuck on company-suffixed slugs be corrected.
--
-- Shared-schema note: RateMyManagers reads and writes these rows but does not care how a slug was
-- chosen, and pending rows are unreachable to everyone except the person who submitted them, so no
-- public URL changes here. Werkpages owns this migration because Werkpages owns the migration
-- stream; RMM is frozen at V43.

-- ── 1. Pending rows leave the clean namespace ────────────────────────────────
--
-- Collisions are resolved with the row id, which is unique by definition, rather than a counter
-- that would need a loop. A row already inside the namespace is left alone so this is re-runnable.
UPDATE managers m
   SET slug = CASE
                WHEN EXISTS (SELECT 1 FROM managers x
                              WHERE x.slug = m.slug || '-pending' AND x.id <> m.id)
                THEN m.slug || '-pending-' || m.id
                ELSE m.slug || '-pending'
              END,
       updated_at = now()
 WHERE m.approval_status = 'pending_approval'
   AND m.slug IS NOT NULL
   AND m.slug NOT LIKE '%-pending'
   AND m.slug NOT LIKE '%-pending-%';

-- ── 2. Live managers reclaim the clean name a pending row had been squatting on ──
--
-- Only where the clean name is now genuinely free, and only for rows that are published. The old
-- URL is recorded first: these ARE public, some are indexed, and a slug change without history is
-- a 404 for every link that already points at it.
CREATE TEMP TABLE reclaimable ON COMMIT DROP AS
SELECT m.id,
       regexp_replace(lower(btrim(m.name)), '[^a-z0-9]+', '-', 'g') AS clean_slug
  FROM managers m
 WHERE m.approval_status IN ('approved', 'ghost')
   AND m.slug IS NOT NULL;

DELETE FROM reclaimable r
 WHERE r.clean_slug = '' 
    OR r.clean_slug IS NULL
    -- already on the clean name, nothing to do
    OR EXISTS (SELECT 1 FROM managers m WHERE m.id = r.id AND m.slug = r.clean_slug)
    -- somebody else holds it; that is a merge decision for an admin, not a migration
    OR EXISTS (SELECT 1 FROM managers m WHERE m.slug = r.clean_slug AND m.id <> r.id)
    -- two live managers want the same clean name: leave both, let an admin choose
    OR (SELECT count(*) FROM reclaimable r2 WHERE r2.clean_slug = r.clean_slug) > 1;

INSERT INTO manager_url_history (manager_id, company_slug, manager_slug)
SELECT m.id, c.slug, m.slug
  FROM managers m
  JOIN reclaimable r ON r.id = m.id
  JOIN companies c ON c.id = m.company_id
 WHERE m.slug IS NOT NULL
   -- manager_url_history.company_slug is NOT NULL. A manager with no company never had a nested
   -- URL, so there is nothing to remember; an inner join skips exactly those.
   AND c.slug IS NOT NULL
ON CONFLICT DO NOTHING;

UPDATE managers m
   SET slug = r.clean_slug,
       updated_at = now()
  FROM reclaimable r
 WHERE m.id = r.id;
