-- Fix 1: Rebuild the existing user_id-based unique index using COALESCE so that
-- null manager_title/company values are treated as empty string and conflict correctly.
DROP INDEX IF EXISTS idx_reviews_user_manager_role;
CREATE UNIQUE INDEX idx_reviews_user_manager_role
    ON reviews (user_id, manager_id,
                LOWER(TRIM(COALESCE(manager_title, ''))),
                LOWER(TRIM(COALESCE(manager_company, ''))))
    WHERE user_id IS NOT NULL AND weight = FALSE;

-- Fix 2: Prevent the same author name from submitting duplicate reviews for the
-- same manager+role, regardless of whether the submission was anonymous or logged-in.
-- This closes the gap where a user submits a drop-off review (user_id=NULL) and then
-- submits again while logged in — both share the same author username, so this index
-- catches the conflict that the user_id-based index cannot.
CREATE UNIQUE INDEX idx_reviews_author_manager_role
    ON reviews (LOWER(TRIM(author)), manager_id,
                LOWER(TRIM(COALESCE(manager_title, ''))),
                LOWER(TRIM(COALESCE(manager_company, ''))))
    WHERE weight = FALSE;
