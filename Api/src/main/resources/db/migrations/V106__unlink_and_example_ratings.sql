-- ─────────────────────────────────────────────────────────────────────────────
-- V106  UNLINK RATING, AND PLATFORM-GENERATED EXAMPLE RATINGS
--
-- Two approved product changes that both need schema, so they share one migration
-- rather than two: WerkPages owns the stream and a single transaction is one
-- recovery decision instead of two.
--
--   1. reviews.origin          - tells a contributor's rating apart from a
--                                platform-generated example, so examples can be
--                                excluded from every genuine calculation.
--   2. reviews.unlinked_at     - a terminal severance. Not a withdrawal.
--   3. rating_participation    - the minimal private record that an account has
--                                already rated a subject, which survives unlinking
--                                and prevents a second rating.
--
-- Idempotent throughout: every statement is IF NOT EXISTS or guarded, so a
-- re-run after a partial failure changes nothing.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. PROVENANCE ───────────────────────────────────────────────────────────
--
-- WHY A COLUMN RATHER THAN THE EXISTING weight FLAG.
--
-- Seeds are identifiable today as `user_id IS NULL AND weight = TRUE`, and that
-- predicate is already used as the seed test in ManagerRepository's
-- seed_reviews/real_reviews split. It is reliable, but it is a coincidence of two
-- columns that mean other things: `weight` is about whether a rating counts
-- toward a cached average during its weighting window, and `user_id IS NULL` is
-- also true of every anonymised and every restored rating. Overloading it means
-- the day somebody anonymises a genuine rating that still carries weight, that
-- rating silently becomes an "example".
--
-- So provenance gets its own column, backfilled from the predicate that is
-- correct TODAY, and nothing afterwards infers provenance from anonymity, age,
-- score or a null user_id.

ALTER TABLE reviews
  ADD COLUMN IF NOT EXISTS origin TEXT NOT NULL DEFAULT 'contributor';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reviews_origin_known') THEN
    ALTER TABLE reviews ADD CONSTRAINT reviews_origin_known
      CHECK (origin IN ('contributor', 'platform_example'));
  END IF;
END $$;

/*
  THE BACKFILL, and the one claim it makes.

  `user_id IS NULL AND weight = TRUE` is set by createSeedReview and by no other
  write path in either product - verified by grepping every INSERT and UPDATE
  touching `weight`. A genuine submission takes the column default, FALSE.

  Restricted to rows that are STILL seeds by that test, and run before anything
  else can write `origin`, so it cannot reclassify a contributor's rating.
*/
UPDATE reviews
   SET origin = 'platform_example'
 WHERE origin = 'contributor'
   AND user_id IS NULL
   AND weight = TRUE;

CREATE INDEX IF NOT EXISTS reviews_origin_example
  ON reviews (manager_id) WHERE origin = 'platform_example';

/*
  AMBIGUOUS ROWS ARE REPORTED, NOT GUESSED.

  A row with weight = TRUE and a user_id should not exist: no path writes it. If
  any do, they are NOT classified - they keep origin = 'contributor', which is
  the safe direction, because misclassifying a real employee's rating as a
  demonstration is far worse than leaving a seed uncounted as one. The notice
  gives the operator the count to investigate.
*/
DO $$
DECLARE
  v_examples  BIGINT;
  v_ambiguous BIGINT;
BEGIN
  SELECT count(*) INTO v_examples  FROM reviews WHERE origin = 'platform_example';
  SELECT count(*) INTO v_ambiguous FROM reviews WHERE weight = TRUE AND user_id IS NOT NULL;

  RAISE NOTICE 'V106: % ratings classified as platform examples', v_examples;
  IF v_ambiguous > 0 THEN
    RAISE NOTICE 'V106: % rows carry weight = TRUE WITH a user_id. These are NOT '
                 'classified and remain contributor ratings. No write path produces '
                 'this shape; investigate before treating any of them as examples.',
                 v_ambiguous;
  END IF;
END $$;

-- ── 2. UNLINKING ────────────────────────────────────────────────────────────
--
-- A WITHDRAWAL AND AN UNLINK ARE DIFFERENT OPERATIONS, and conflating them is the
-- defect this column exists to prevent.
--
--   withdrawal (today)  hides the rating, and restoreExpiredDeletions brings it
--                       back, published and anonymous, after three days
--   unlink (new)        the rating NEVER leaves publication; the contributor's
--                       ownership is severed immediately and permanently
--
-- So an unlinked rating must be invisible to the restoration sweep: it was never
-- withdrawn, so there is nothing to restore, and a sweep that touched it could
-- republish an already-published row.

ALTER TABLE reviews
  ADD COLUMN IF NOT EXISTS unlinked_at TIMESTAMPTZ;

DO $$
BEGIN
  -- An unlinked rating has no owner. The severance and the flag are one fact, so
  -- the database refuses the half-state rather than trusting every writer.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reviews_unlinked_has_no_owner') THEN
    ALTER TABLE reviews ADD CONSTRAINT reviews_unlinked_has_no_owner
      CHECK (unlinked_at IS NULL OR user_id IS NULL);
  END IF;

  -- An example was never anybody's to unlink.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reviews_examples_are_not_unlinked') THEN
    ALTER TABLE reviews ADD CONSTRAINT reviews_examples_are_not_unlinked
      CHECK (origin = 'contributor' OR unlinked_at IS NULL);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS reviews_unlinked
  ON reviews (unlinked_at) WHERE unlinked_at IS NOT NULL;

-- ── 3. THE PRIVATE PARTICIPATION RECORD ─────────────────────────────────────
--
-- WHY NOT rating_opportunities, which already exists and looks like this.
--
-- That table models an ALLOWANCE: `ordinal IN (1,2)`, so an account may rate one
-- subject twice - a contributor with two separate employments under one manager
-- legitimately has two. It is also audited, merged and retired by machinery this
-- migration must not disturb.
--
-- This table records a different and terminal fact: that an account has
-- participated, full stop. Expressing that inside rating_opportunities would mean
-- inserting rows for ordinals nobody opened, inventing an `opened_at` and an
-- `origin_claim_id` for them. That is fabricated data, so it is not done.
--
-- Polymorphic in the same shape as rating_opportunities, because the same rule is
-- wanted for companies and interviews and a manager-only table would have to be
-- replaced the moment it is.

CREATE TABLE IF NOT EXISTS rating_participation (
  -- CASCADE, matching rating_opportunities, and with the same honest limitation:
  -- the record is only as durable as the account, so deleting and re-registering
  -- resets it. Closing that needs an identity-level limit, which is not designed
  -- here and is not pretended at.
  user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  subject_type TEXT        NOT NULL,
  subject_id   BIGINT      NOT NULL,

  first_rated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

  /*
    DID THIS PARTICIPATION COUNT TOWARD THE PROFILE GATE.

    One boolean, and it is here because the gate and the duplicate-prevention rule are two
    different questions that this one table has to answer after unlinking severs the rating.

      duplicate prevention  has this account rated this subject at all
      the gate              has this account made a contribution that EARNS full profile access

    Not every rating earns the gate - `reviews.gate_eligible` already decides that - so recording
    participation without it would hand full access to somebody whose only rating never qualified.
    And recording only gate-eligible participation would stop the duplicate rule working for the
    rest. Hence both facts, in one row.

    It carries no rating id, no score, no date beyond when they first participated, and no claim.
  */
  gate_eligible  BOOLEAN     NOT NULL DEFAULT FALSE,

  /*
    WHEN THIS ACCOUNT RELINQUISHED ITS CLAIM ON THIS SUBJECT, if it has.

    This column, and not the mere existence of the row, is what blocks a second rating - and the
    distinction matters because Rev 268 deliberately allows an account TWO ratings of one subject
    (`rating_opportunities.ordinal IN (1,2)`): a contributor with two separate employments under
    one manager legitimately has two.

    So:
      row exists, unlinked_at IS NULL      participated, still owns it, second role still allowed
      row exists, unlinked_at IS NOT NULL  relinquished; no further rating of this subject, ever

    Blocking on the row alone would have revoked that second allowance from every contributor who
    never unlinked anything, which is the "do not break legitimate existing rules" case.
  */
  unlinked_at    TIMESTAMPTZ,

  -- NO COLUMN IN THIS TABLE MAY EVER HOLD A RATING ID, for the reason stated at
  -- length on rating_opportunities: a value equal to a review id, in a row that
  -- also carries user_id, recovers the author of an anonymised rating in one
  -- query and needs no foreign key to do it. "The reference points the other way"
  -- is not a defence; a join has no direction.
  --
  -- Nor any score, employment date, claim id or review content. The table answers
  -- exactly one question - has this account rated this subject - and anything
  -- more would make it a second, quieter copy of the thing unlinking removed.

  CONSTRAINT participation_subject_known
    CHECK (subject_type IN ('manager','company','interview')),

  CONSTRAINT participation_one_per_subject
    PRIMARY KEY (user_id, subject_type, subject_id)
);

-- The merge reads by subject with no user_id, exactly as it does for
-- rating_opportunities, so it needs the same index or it scans a table that grows
-- with every rating ever submitted.
CREATE INDEX IF NOT EXISTS rating_participation_subject
  ON rating_participation (subject_type, subject_id);

/*
  BACKFILL FROM EXISTING OWNERSHIP.

  Every rating that is still owned proves its owner participated. Examples are
  excluded by origin - a demonstration tile is nobody's participation and must
  never grant eligibility (requirement 6).

  Deliberately NOT backfilled from anonymised or already-restored ratings: their
  user_id is gone, and inventing participation for them would mean guessing who
  wrote them, which is the one thing this whole design refuses to do. The
  consequence is stated rather than hidden: a contributor who anonymised a rating
  before this migration is not recorded as having participated, and could rate
  that subject again. That is a pre-existing property of anonymisation, not a
  regression introduced here.
*/
INSERT INTO rating_participation (user_id, subject_type, subject_id, first_rated_at, gate_eligible)
SELECT r.user_id, 'manager', r.manager_id, min(r.created_at),
       -- bool_or: one qualifying rating among several on the same manager earns the gate, which
       -- is exactly what the live query it replaces does with EXISTS.
       bool_or(r.gate_eligible AND r.disposition = 'live' AND r.deleted_at IS NULL)
  FROM reviews r
 WHERE r.user_id IS NOT NULL
   AND r.origin = 'contributor'
 GROUP BY r.user_id, r.manager_id
ON CONFLICT (user_id, subject_type, subject_id) DO NOTHING;

DO $$
DECLARE v_rows BIGINT;
BEGIN
  SELECT count(*) INTO v_rows FROM rating_participation;
  RAISE NOTICE 'V106: % participation records backfilled from owned manager ratings', v_rows;
END $$;
