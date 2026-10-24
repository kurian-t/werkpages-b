-- Publishing a captured form as a real, anonymous rating.
--
-- A workplace rating or an interview experience that somebody filled in completely and then lost at
-- the sign-in wall is kept in `captured_drafts` (V76), where an admin could read it and nothing
-- else. A manager rating dropped off at the same wall has always become a real `reviews` row with
-- `user_id` NULL, reviewed through the manager's own pending_approval gate. These two tables get
-- the same ending: an admin can publish a complete draft, and what appears is an ordinary
-- anonymous rating that counts in every aggregate.
--
-- Everything here is additive and nullable. RMM reads this schema and does not migrate it, so no
-- existing write path in either application can be invalidated by this file.

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. Which captured form a published rating came from
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Carried ONLY by a row that was published from a draft, never by a rating somebody submitted
-- while signed in. That is what makes it usable as an identity: the token answers "is there
-- already an anonymous publication of this form", which is the question both the admin publish
-- path and the author's return have to agree on.
--
-- The author's return is the reason it has to be stored at all. Somebody who gets bounced to
-- sign-in, comes back, and submits properly must end up with ONE rating, not theirs plus the
-- anonymous copy an admin published while they were away. The authenticated path finds that copy
-- by token and removes it in the same transaction that writes the real one.
ALTER TABLE company_reviews   ADD COLUMN draft_token UUID;
ALTER TABLE interview_reviews ADD COLUMN draft_token UUID;

-- UNIQUE, not merely indexed. An index would make the lookup fast and still allow two
-- publications of one form, which is the exact duplicate this feature could introduce: two admins
-- on the queue at once, or two `captured_drafts` rows that share a token because the capture path
-- inserts a fresh row every time somebody is bounced.
--
-- Partial on `deleted_at` as well, matching company_reviews_one_per_user_company. The rule is one
-- LIVE publication per form, so a rating a moderator removed keeps its token and stops being a
-- reason the constraint blocks anything. That is deliberate: the draft it came from is already
-- resolved and cannot be published again, so the only thing the index would be preventing is a
-- publication that could not happen anyway.
--
-- Safe against existing data by construction: the columns above are new, so every row has NULL
-- here and NULLs are distinct in a unique index.
CREATE UNIQUE INDEX company_reviews_draft_token
    ON company_reviews (draft_token)
    WHERE draft_token IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX interview_reviews_draft_token
    ON interview_reviews (draft_token)
    WHERE draft_token IS NOT NULL AND deleted_at IS NULL;

COMMENT ON COLUMN company_reviews.draft_token IS
    'The captured form this rating was published from, or NULL when somebody submitted it signed '
    'in. Lets the author''s later authenticated submission find and remove the anonymous copy.';

COMMENT ON COLUMN interview_reviews.draft_token IS
    'The captured form this review was published from, or NULL when somebody submitted it signed '
    'in. Lets the author''s later authenticated submission find and remove the anonymous copy.';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. Interview reviews gain the moderation axis the other two tables already have
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- `reviews` has had these since V84 and `company_reviews` since V88, and both restore sweeps use
-- `deleted_reason IS NULL` to tell an author's withdrawal from a moderator's removal. Interview
-- reviews had neither the columns nor a sweep, so a withdrawn interview experience never came
-- back at all.
--
-- The sweep is wired up in this release. Without these columns it would have no way to leave a
-- moderated row deleted, so the two have to arrive together: a sweep that resurrects whatever an
-- admin removed is worse than no sweep.
--
-- There is no moderator deletion path for interview reviews today, so nothing writes these yet.
-- They are the condition under which the sweep is safe, not a feature on their own.
ALTER TABLE interview_reviews ADD COLUMN deleted_reason TEXT;
ALTER TABLE interview_reviews ADD COLUMN deleted_by     UUID REFERENCES users(id);

COMMENT ON COLUMN interview_reviews.deleted_reason IS
    'Set when a moderator removed this review. NULL means the author withdrew it, which is what '
    'the three-day restore sweep tests before bringing a row back.';

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 3. What became of each captured draft
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- `reviewed_at` alone says an admin dealt with a draft and nothing about how. Publishing one
-- creates public data from an unverified submission, so the trail has to say which drafts became
-- ratings, which were thrown away, and which stopped mattering because their author came back.
--
--   'published'  an admin turned it into a real anonymous rating; resolved_id is that rating
--   'dismissed'  an admin read it and did not publish it
--   'superseded' the author returned, signed in, and submitted it themselves
--
-- NULL alongside a set `reviewed_at` is possible on rows resolved before this migration, and is
-- read as 'dismissed', which is what marking one reviewed used to mean.
ALTER TABLE captured_drafts ADD COLUMN resolution  TEXT;
ALTER TABLE captured_drafts ADD COLUMN resolved_id UUID;
ALTER TABLE captured_drafts ADD COLUMN resolved_by UUID REFERENCES users(id);

ALTER TABLE captured_drafts
    ADD CONSTRAINT captured_drafts_resolution_known
    CHECK (resolution IS NULL OR resolution IN ('published', 'dismissed', 'superseded'));

COMMENT ON COLUMN captured_drafts.resolution IS
    'published | dismissed | superseded. NULL on an unresolved draft, and on rows marked reviewed '
    'before V96, where it means dismissed.';

COMMENT ON COLUMN captured_drafts.resolved_id IS
    'The rating this draft became: company_reviews.id or interview_reviews.id when published, and '
    'the author''s own rating when superseded. No FK, because it names one of two tables.';
