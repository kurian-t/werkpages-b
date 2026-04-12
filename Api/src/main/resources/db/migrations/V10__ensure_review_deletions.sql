-- V8 was recorded in flyway_schema_history but the DDL never executed on production.
-- This migration ensures the table exists regardless.
CREATE TABLE IF NOT EXISTS review_deletions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    manager_id  BIGINT      NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    deleted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_review_deletions_user_manager
    ON review_deletions (user_id, manager_id, deleted_at DESC);
