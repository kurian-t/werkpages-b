-- V7: Consolidates post-V6 schema changes for production deployment.

-- 1. Add start/end date tracking to manager edit requests
ALTER TABLE manager_edits ADD COLUMN new_start_date TIMESTAMPTZ;
ALTER TABLE manager_edits ADD COLUMN new_end_date   TIMESTAMPTZ;

-- 2. Expand unique review constraint to include company
--    (V6 created this index with title only — drop and recreate with title+company)
DROP INDEX IF EXISTS idx_reviews_user_manager_role;
CREATE UNIQUE INDEX IF NOT EXISTS idx_reviews_user_manager_role
    ON reviews (user_id, manager_id, LOWER(TRIM(manager_title)), LOWER(TRIM(manager_company)));

-- 3. Track when the manager held this role (for date validation)
ALTER TABLE reviews ADD COLUMN manager_role_start DATE;
ALTER TABLE reviews ADD COLUMN manager_role_end   DATE;
