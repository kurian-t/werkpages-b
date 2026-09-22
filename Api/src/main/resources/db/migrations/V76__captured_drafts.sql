-- Partial submissions, kept rather than thrown away.
--
-- Every contribution form ends at a wall: fill it in, press submit, and an unauthenticated person
-- is sent to sign in. Many do not come back, and everything they wrote is lost at exactly the
-- moment it was complete enough to be worth something.
--
-- The manager forms already captured at that moment, into real `pending_approval` rows an admin can
-- see. The workplace rating form did not: the whole rating was assembled, the person was redirected,
-- and nothing survived. This table is where the forms that have no domain row to create put what
-- they had, so an admin can read it.
--
-- Deliberately NOT a half-written row in company_reviews or interview_reviews. Those feed public
-- aggregates and carry NOT NULL columns that a partial form cannot satisfy; relaxing them so a
-- draft fits would weaken the constraints that keep published data sound, and a draft that leaked
-- into an average would be worse than a draft that was never kept.

CREATE TABLE IF NOT EXISTS captured_drafts (
    id            BIGSERIAL PRIMARY KEY,
    -- Which form it came from: 'company_rating' | 'interview' | 'manager_review'.
    kind          TEXT        NOT NULL,
    -- The company or manager it was about, when the form knew. No FK: a draft may name a company
    -- that does not exist yet, and an admin deleting one must not delete the evidence.
    company_id    BIGINT,
    manager_id    BIGINT,
    -- What the form held, verbatim. JSONB rather than columns per form: three forms with different
    -- fields would otherwise mean three tables, and nothing reads individual keys in SQL.
    payload       JSONB       NOT NULL,
    -- Who, when known. A draft is captured precisely because they were not signed in, so this is
    -- usually null; it is set when a signed-in person abandons instead.
    user_id       UUID        REFERENCES users(id) ON DELETE SET NULL,
    -- Links a draft to the real submission that followed, so completing the form removes the draft
    -- rather than leaving an admin to review something that was finished a minute later.
    draft_token   UUID,
    reviewed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The admin queue reads newest-first and filters to unreviewed.
CREATE INDEX IF NOT EXISTS captured_drafts_queue
    ON captured_drafts (created_at DESC) WHERE reviewed_at IS NULL;

-- The submit path deletes by token before inserting the real row.
CREATE INDEX IF NOT EXISTS captured_drafts_token
    ON captured_drafts (draft_token) WHERE draft_token IS NOT NULL;

COMMENT ON TABLE captured_drafts IS
    'What a contribution form held when somebody walked away from it, for forms that have no '
    'domain row to create. Never published, never aggregated - read only by the admin panel. The '
    'manager forms capture into pending_approval rows instead, which an admin already reviews.';

COMMENT ON COLUMN captured_drafts.draft_token IS
    'Client-generated, carried into the real submission so the draft can be deleted when the form '
    'is finished. Without it an admin reviews drafts whose authors came back and completed them.';
