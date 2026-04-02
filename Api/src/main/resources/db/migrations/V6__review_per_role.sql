-- One review per user per manager per role title (case-insensitive, whitespace-normalised).
-- Replaces the previous implicit front-end-only constraint.
CREATE UNIQUE INDEX IF NOT EXISTS idx_reviews_user_manager_role
    ON reviews (user_id, manager_id, LOWER(TRIM(manager_title)));
