-- Withdrawing a workplace rating behaves like withdrawing a manager rating.
--
-- A manager rating you delete is a THREE DAY soft delete: user_id is nulled at once, the row
-- disappears, and it returns anonymous when the window expires. The opinion survives because it
-- is still true and the corpus is still better for it; what goes is the link to the person.
--
-- A workplace rating could only be deleted outright, so the same act had two different meanings
-- depending on which form you had filled in.
--
-- TWO COLUMNS ARE NEEDED FOR THAT.
--
-- user_id NULLABLE
--   Anonymising means unlinking the row from the account, exactly as reviews.delete() does. The
--   author handle stays - the rating keeps the identity readers already saw - so this removes the
--   account link, not the pseudonym. NOT NULL made that impossible.
--
--   Safe against the one-per-person index: company_reviews_one_per_user_company is partial on
--   deleted_at IS NULL, and Postgres treats NULLs as distinct in a unique index, so neither a
--   withdrawn row nor several restored anonymous ones can collide.
--
-- deleted_reason
--   What separates a withdrawal from a moderation decision. The sweep restores only rows with no
--   reason. Without this column a rating an admin removed would come back three days later, live
--   and counting, with nothing on the page to show it had ever been actioned - which is exactly
--   what happened to manager reviews before V84 added the same column there.
ALTER TABLE company_reviews
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE company_reviews
    ADD COLUMN deleted_reason TEXT;

COMMENT ON COLUMN company_reviews.user_id IS
    'Null once the author withdraws: the rating returns anonymous after the 3-day window, keeping '
    'its author handle but no link to the account. Mirrors reviews.user_id.';

COMMENT ON COLUMN company_reviews.deleted_reason IS
    'Set only by a moderator. A reason means a person decided and the row is never restored; no '
    'reason means an author withdrew and the daily sweep brings it back. Mirrors reviews.deleted_reason.';
