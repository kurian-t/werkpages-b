-- Enforce at most one seed (placeholder) review per manager.
-- Seed reviews have weight = TRUE and user_id = NULL, so the existing
-- unique index on (user_id, manager_id, ...) does not cover them (NULLs
-- are never equal in a unique index). This partial index closes that gap.
CREATE UNIQUE INDEX idx_one_seed_per_manager
    ON reviews (manager_id)
    WHERE weight = TRUE;
