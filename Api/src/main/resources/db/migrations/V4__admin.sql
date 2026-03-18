-- Add role column to users
ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin'));

-- Banned users table
CREATE TABLE banned_users (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason      TEXT NOT NULL,
    banned_by   TEXT NOT NULL,
    banned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id)
);

-- Pending manager edit requests
CREATE TABLE pending_manager_edits (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    manager_id      BIGINT NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    proposed_by     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    new_company     TEXT,
    new_title       TEXT,
    status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pending_edits_manager_id ON pending_manager_edits(manager_id);
CREATE INDEX idx_pending_edits_status ON pending_manager_edits(status);
