-- V7: Add pending_approval/rejected manager statuses, submitted_by tracking, and notifications table

-- Extend the status check constraint to include new statuses
ALTER TABLE managers DROP CONSTRAINT IF EXISTS managers_status_check;
ALTER TABLE managers ADD CONSTRAINT managers_status_check
    CHECK (status IN ('active', 'retired', 'pending_approval', 'rejected'));

-- Track who submitted each manager (used for approval notifications)
ALTER TABLE managers ADD COLUMN IF NOT EXISTS submitted_by UUID REFERENCES users(id) ON DELETE SET NULL;

-- Notifications table
CREATE TABLE IF NOT EXISTS notifications (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type        VARCHAR(50) NOT NULL,
    title       TEXT        NOT NULL,
    message     TEXT        NOT NULL,
    read        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_unread  ON notifications(user_id, read) WHERE read = FALSE;
