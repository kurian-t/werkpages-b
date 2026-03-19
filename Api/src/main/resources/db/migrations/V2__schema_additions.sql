-- V2: All schema additions built on top of V1

-- ── Users: role ──────────────────────────────────────────────────────────────
ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin'));

-- ── Managers: submitter tracking + approval workflow ─────────────────────────
ALTER TABLE managers ADD COLUMN submitted_by    UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE managers ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'approved'
    CHECK (approval_status IN ('pending_approval', 'approved', 'rejected'));

CREATE INDEX idx_managers_approval_status ON managers(approval_status);

-- ── Reviews: work period ─────────────────────────────────────────────────────
ALTER TABLE reviews ADD COLUMN worked_from  DATE;
ALTER TABLE reviews ADD COLUMN worked_until DATE;

CREATE INDEX idx_reviews_worked_from ON reviews(worked_from);

-- ── Banned users ─────────────────────────────────────────────────────────────
CREATE TABLE banned_users (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason    TEXT NOT NULL,
    banned_by TEXT NOT NULL,
    banned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id)
);

-- ── Manager edit requests (pending queue + audit history) ────────────────────
CREATE TABLE manager_edits (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    manager_id  BIGINT      NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    proposed_by UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    new_company TEXT,
    new_title   TEXT,
    new_status  VARCHAR(20),
    status      TEXT        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'rejected')),
    reviewed_at TIMESTAMPTZ,
    reviewed_by UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_manager_edits_manager_id ON manager_edits(manager_id);
CREATE INDEX idx_manager_edits_status     ON manager_edits(status);
-- Ensures a user can only have one pending edit per manager (enables upsert)
CREATE UNIQUE INDEX idx_manager_edits_one_pending ON manager_edits(manager_id, proposed_by) WHERE status = 'pending';

-- ── Notifications ─────────────────────────────────────────────────────────────
CREATE TABLE notifications (
    id         UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       VARCHAR(50) NOT NULL,
    title      TEXT        NOT NULL,
    message    TEXT        NOT NULL,
    read       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_unread  ON notifications(user_id, read) WHERE read = FALSE;
