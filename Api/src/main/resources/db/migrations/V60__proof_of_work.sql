-- Proof of work: holding a rating until we know the rater actually worked there.
--
-- The problem is a gate bypass, not vanity. Manager data unlocks on
-- EXISTS(SELECT 1 FROM reviews WHERE user_id = ...), so ANY review is a working key - and the
-- cheapest key anyone could cut was a famous name we printed in our own form placeholder. Rating
-- a CEO nobody ever reported to bought exactly the access that rating a real manager buys.
--
-- Everything here is additive, and every default reproduces today's behaviour. On deploy, every
-- existing review keeps publishing and keeps counting: nobody honest loses access to data they
-- already earned. RMM shares this schema and omits the new columns in its INSERTs, which stays
-- valid for the same reason.

-- ── Reviews: publication and gate eligibility, as separate facts ─────────────────────────────
--
-- One nullable column cannot mean three things. Publication decides whether anyone can see the
-- rating; gate eligibility decides whether it counts as a contribution; the challenge table below
-- tracks where the proof process is. Today they move together, but soft deletion already divorces
-- the first two - a deleted review is unpublished and still counts against the day's allowance -
-- and moderating a rating's text while keeping its scores is the next case.
ALTER TABLE reviews ADD COLUMN disposition TEXT NOT NULL DEFAULT 'live'
    CHECK (disposition IN ('live', 'held', 'rejected'));

ALTER TABLE reviews ADD COLUMN gate_eligible BOOLEAN NOT NULL DEFAULT TRUE;

-- What we thought of the author at the moment of writing. Scores move; when an admin looks at a
-- rating months later they need to know what we knew when it published, not what we know now.
ALTER TABLE reviews ADD COLUMN author_confidence INT;

-- When this rating most recently became live. NOT created_at: a rating held for 29 days and then
-- approved has stood for a day, not for thirty, and paying the standing credit on age alone would
-- reward exactly the behaviour this feature exists to slow down.
--
-- The DEFAULT is load-bearing for the shared database, not a convenience. The coherence constraint
-- below requires a live row to carry a live_since, and neither RMM's INSERTs nor most of ours name
-- this column - so without a default, every ordinary review insert on either backend would start
-- failing the moment this migration lands. Held inserts pass NULL explicitly.
ALTER TABLE reviews ADD COLUMN live_since TIMESTAMPTZ DEFAULT now();

-- An annotation for the admin queue on a rating that publishes anyway. The near miss - a listed
-- name at a company that is not theirs - must not mark its author, because marking them would
-- hold their next rating, and the whole point is that this one is not being treated as abuse. So
-- the note lands on the rating rather than on the person.
ALTER TABLE reviews ADD COLUMN admin_flag TEXT;

COMMENT ON COLUMN reviews.disposition   IS 'Publication: live | held | rejected.';
COMMENT ON COLUMN reviews.gate_eligible IS 'Whether this review counts as a contribution.';
COMMENT ON COLUMN reviews.live_since    IS 'When this review most recently became live.';

-- The ADD COLUMN above backfilled every existing row to now(); rewrite that to when the review
-- was actually written, so nobody's standing credit is reset by the deploy itself. Soft-deleted
-- rows are backfilled too: deletion is its own axis, so a deleted review keeps disposition 'live'
-- and the publication predicate excludes it via deleted_at rather than by changing disposition.
UPDATE reviews SET live_since = created_at WHERE disposition = 'live';

-- Splitting the state machines multiplies the invalid states, so name the legal ones. The
-- constraint deliberately says nothing about gate_eligible while a rating is live - that is the
-- one axis left free, because publishing without crediting is the case the split exists to serve.
ALTER TABLE reviews ADD CONSTRAINT reviews_state_coherent CHECK (
    CASE disposition
        WHEN 'live' THEN live_since IS NOT NULL
        ELSE gate_eligible = FALSE AND live_since IS NULL
    END
);

CREATE INDEX reviews_published ON reviews (manager_id)
    WHERE disposition = 'live' AND deleted_at IS NULL;

-- The WHOLE publication predicate, so no caller is left holding half of it. A view encoding half
-- a rule is worse than no view, because it looks finished. Anything needing the raw set - admin
-- surfaces, the expired-weight sweep - reads `reviews` directly and says so.
CREATE VIEW published_reviews AS
    SELECT * FROM reviews
    WHERE disposition = 'live'
      AND deleted_at IS NULL;

-- ── Confidence ──────────────────────────────────────────────────────────────────────────────
--
-- Per account, starting at 70. Stored twice on purpose: a cached integer for the fast check on
-- every submission, and an append-only log that is the source of truth. "This person is at 32" is
-- unactionable; "32, because they abandoned a challenge on Satya Nadella and had a manager
-- rejected" can be acted on, and reversed when it was wrong.
ALTER TABLE users ADD COLUMN confidence INT NOT NULL DEFAULT 70
    CHECK (confidence BETWEEN 0 AND 100);

CREATE TABLE user_confidence_events (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delta       INT  NOT NULL,
    reason      TEXT NOT NULL,
    -- Where this event came from, so the same cause can never be counted twice.
    source_type TEXT NOT NULL CHECK (source_type IN ('challenge', 'manager', 'review')),
    source_id   TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Idempotency as a constraint rather than a convention. Every one of these events can fire twice
-- - a retry, a double-click, a rerun of the daily sweep, a deploy that replays a queue - and
-- debiting somebody 15 twice for one abandonment silently destroys an account's standing.
CREATE UNIQUE INDEX user_confidence_events_once
    ON user_confidence_events (user_id, reason, source_type, source_id);

CREATE INDEX user_confidence_events_user ON user_confidence_events (user_id, created_at DESC);

-- ── High-profile figures ────────────────────────────────────────────────────────────────────
--
-- A name is not an identity. Matching on a bare string would challenge a site manager genuinely
-- called Tim Cook, at a construction firm with a team of eleven, to prove he knows himself -
-- which breaks the one rule this whole feature is built around.
CREATE TABLE high_profile_figures (
    id         BIGSERIAL PRIMARY KEY,
    -- The real answer, and the one to prefer: it names a row, so there is no ambiguity at all.
    manager_id BIGINT REFERENCES managers(id) ON DELETE CASCADE,
    -- The bootstrapping form. The first person to rate a figure we have never stored has to be
    -- challenged before any manager row exists to point at. Stored normalised, lower and trimmed.
    full_name  TEXT,
    company_id BIGINT REFERENCES companies(id) ON DELETE CASCADE,
    note       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK (manager_id IS NOT NULL OR (full_name IS NOT NULL AND company_id IS NOT NULL))
);

CREATE UNIQUE INDEX high_profile_figures_manager
    ON high_profile_figures (manager_id) WHERE manager_id IS NOT NULL;

CREATE UNIQUE INDEX high_profile_figures_name_company
    ON high_profile_figures (full_name, company_id) WHERE full_name IS NOT NULL;

CREATE INDEX high_profile_figures_name ON high_profile_figures (full_name);

-- ── Proof challenges ────────────────────────────────────────────────────────────────────────
CREATE TABLE manager_proof_challenges (
    id         UUID   PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID   NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    manager_id BIGINT NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    review_id  UUID            REFERENCES reviews(id)  ON DELETE CASCADE,

    reason TEXT NOT NULL
        CHECK (reason IN ('high_profile', 'flagged_user', 'suspicious_name')),

    -- 'open'         - issued, nothing supplied. Flags the author.
    -- 'admin_review' - evidence submitted, waiting on a person. Never auto-resolves.
    -- 'abandoned'    - open and untouched past the deadline. Still flags; still in the queue.
    -- 'verified'     - cleared by a work-email code (affiliation, not relationship).
    -- 'approved'     - cleared by a person reading the claim.
    -- 'rejected'     - refused by a person.
    --
    -- There is deliberately no self-clearing state. An earlier draft let the author withdraw the
    -- rating to lift their own flag, which is a laundering step: challenge a famous name, withdraw
    -- to come out clean, then submit the junk you actually wanted. Deleting the review is still
    -- allowed - it leaves the challenge open, so the flag stands.
    status TEXT NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'admin_review', 'abandoned', 'verified', 'approved', 'rejected')),

    -- Affiliation path. Unused until SES production access exists; the shape is settled now so
    -- adding the sender later is wiring rather than a migration.
    email_domain    TEXT,
    code_hash       TEXT,
    code_expires_at TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0,

    -- Relationship claim. Never published - shown to admins to judge the claim and nowhere else.
    worked_from   DATE,
    worked_until  DATE,
    claimed_title TEXT,
    claimed_org   TEXT,
    relationship  TEXT
        CHECK (relationship IS NULL
               OR relationship IN ('direct_report', 'skip_level', 'other_team')),
    evidence_note TEXT,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    resolved_at  TIMESTAMPTZ,
    resolved_by  UUID REFERENCES users(id) ON DELETE SET NULL,

    CHECK (worked_until IS NULL OR worked_from IS NULL OR worked_until >= worked_from)
);

-- "Is this author flagged?" runs on every submission, so it gets its own partial index. All three
-- unresolved states flag: evidence under review is not yet proof, and walking away is not a way
-- out.
CREATE INDEX manager_proof_challenges_flagging
    ON manager_proof_challenges (user_id)
    WHERE status IN ('open', 'admin_review', 'abandoned');

-- The admin queue. It must cover 'open' and 'abandoned' as well as 'admin_review': with no
-- self-clearing path, a challenge the author simply walked away from would otherwise flag them
-- forever while never appearing in front of anyone who could lift it - a lock with no key.
CREATE INDEX manager_proof_challenges_queue
    ON manager_proof_challenges (status, created_at DESC);

-- One live challenge per person per manager. Re-submitting updates the existing row rather than
-- stacking duplicates in the queue; a resolved one does not block a later attempt.
CREATE UNIQUE INDEX manager_proof_challenges_one_open
    ON manager_proof_challenges (user_id, manager_id)
    WHERE status IN ('open', 'admin_review', 'abandoned');

-- ── Verification sends ──────────────────────────────────────────────────────────────────────
--
-- Rate limiting for the affiliation path. Four of the five limits bound a person; only the global
-- daily count bounds the bill, because per-user caps multiply by the number of accounts and
-- accounts are not bounded.
CREATE TABLE verification_sends (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    challenge_id UUID   REFERENCES manager_proof_challenges(id) ON DELETE CASCADE,
    -- An HMAC, not the address: enough to count sends against a destination without storing a
    -- mailbox we have no reason to keep. The domain is kept plain because the affiliation check
    -- needs it and it identifies nobody on its own.
    email_hmac   TEXT NOT NULL,
    domain       TEXT NOT NULL,
    outcome      TEXT NOT NULL DEFAULT 'sent'
        CHECK (outcome IN ('sent', 'bounced', 'complained')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX verification_sends_daily ON verification_sends (created_at DESC);
CREATE INDEX verification_sends_user  ON verification_sends (user_id, created_at DESC);
CREATE INDEX verification_sends_dest  ON verification_sends (email_hmac, created_at DESC);
