-- Deleting an interview experience behaves like deleting a manager review.
--
-- The row is detached from its author immediately and hidden for a few days, then returns as an
-- anonymous data point. The reasoning is the same as for manager reviews: what someone wrote is
-- theirs to take their name off, but a company should not be able to lose inconvenient feedback
-- because one contributor was talked into removing it.

-- Anonymising means clearing the author, so the column can no longer be NOT NULL.
ALTER TABLE interview_reviews ALTER COLUMN user_id DROP NOT NULL;

-- Restoring a review whose author has since written a NEW one would count the same person twice.
-- Manager reviews solve this with a cooldown on re-reviewing; the same applies here, keyed by
-- company rather than manager.
CREATE TABLE interview_review_deletions (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID   NOT NULL REFERENCES users(id)     ON DELETE CASCADE,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX interview_review_deletions_lookup
    ON interview_review_deletions (user_id, company_id, deleted_at DESC);

-- The one-per-user-per-company-per-year index is partial on deleted_at and keyed on user_id.
-- Once user_id is NULL those rows no longer participate, which is what allows an anonymised
-- review to sit alongside a later one from someone else. The cooldown above is what stops the
-- SAME person filling that gap.
