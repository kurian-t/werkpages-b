CREATE TABLE manager_edits (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    manager_id  BIGINT NOT NULL REFERENCES managers(id),
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    field       VARCHAR(50) NOT NULL,
    old_value   TEXT,
    new_value   TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_manager_edits_manager_id ON manager_edits(manager_id);
CREATE INDEX idx_manager_edits_user_id ON manager_edits(user_id);

ALTER TABLE users ADD COLUMN is_banned BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN banned_reason TEXT;
ALTER TABLE users ADD COLUMN banned_at TIMESTAMPTZ;