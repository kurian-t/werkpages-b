-- V9 was recorded in flyway_schema_history but the ALTER TABLE never
-- executed on production (same issue as V8/V10). This migration adds the
-- column unconditionally with IF NOT EXISTS so it is safe to re-apply.
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS manager_id BIGINT REFERENCES managers(id) ON DELETE SET NULL;
