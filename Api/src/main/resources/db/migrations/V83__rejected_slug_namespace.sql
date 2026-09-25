-- Frees the names held by rejected and removed managers.
--
-- V82 moved pending rows out of the public slug namespace. Rejected rows have the same problem and
-- were missed: a manager an admin explicitly took down went on holding its slug for ever, so the
-- real person it belonged to could never be published under their own name.
--
-- Safe to run at any time. Rejected rows are invisible on every public surface - every listing
-- filters approval_status to 'approved' and 'ghost' - so no live URL moves here and nothing that
-- is currently reachable changes address. That is why this runs automatically while the repairs
-- that DO move live URLs (already-merged managers, managers orphaned by company merges) are an
-- admin-triggered action instead, run when the timing suits rather than on deploy.
--
-- Idempotent: a row already inside the namespace is skipped, so re-running changes nothing. The id
-- breaks ties, since two rejected managers may share a name.
--
-- Werkpages owns this migration because Werkpages owns the migration stream; RMM is frozen at V43
-- and simply reads the result. Adding no column and no constraint, this is backward compatible for
-- both backends.

UPDATE managers m
   SET slug = CASE
                WHEN EXISTS (SELECT 1 FROM managers x
                              WHERE x.slug = m.slug || '-rejected' AND x.id <> m.id)
                THEN m.slug || '-rejected-' || m.id
                ELSE m.slug || '-rejected'
              END,
       updated_at = now()
 WHERE m.approval_status = 'rejected'
   AND m.slug IS NOT NULL
   AND position('-rejected' in m.slug) = 0;
