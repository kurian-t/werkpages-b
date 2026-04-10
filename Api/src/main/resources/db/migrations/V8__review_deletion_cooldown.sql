-- Tracks when a user deletes a review for a manager, to enforce a 30-day re-review cooldown.
CREATE TABLE review_deletions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    manager_id  BIGINT      NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    deleted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_deletions_user_manager ON review_deletions (user_id, manager_id, deleted_at DESC);
