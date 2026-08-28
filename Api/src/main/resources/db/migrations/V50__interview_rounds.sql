-- Per-round interview detail.
--
-- V48 described a process with two flat fields: one `interview_type` and a `rounds` count. That
-- cannot express what a process actually looks like — "phone screen, then a panel, then a VP
-- conversation" collapses to "panel, 3 rounds", which loses the ordering and two of the three
-- formats. The shape of a process is most of what a candidate wants to know before committing
-- three evenings to it.
--
-- Rounds become rows. `interview_reviews.rounds` stays as the count, because it feeds
-- median_rounds in the cached stats, but from here it is DERIVED — a trigger keeps it equal to
-- the number of child rows, so the count and the detail cannot disagree.

CREATE TABLE interview_review_rounds (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    interview_review_id UUID NOT NULL REFERENCES interview_reviews(id) ON DELETE CASCADE,

    -- 1-based position in the process. Ordering is the point: a take-home before a phone screen
    -- is a different experience from the reverse.
    round_number SMALLINT NOT NULL CHECK (round_number BETWEEN 1 AND 10),

    round_type TEXT NOT NULL CHECK (round_type IN (
        'recruiter_screen', 'phone', 'video', 'hiring_manager',
        'technical', 'take_home', 'pair_programming', 'case_study',
        'panel', 'onsite', 'executive'
    )),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One entry per position: a process cannot have two round 2s.
CREATE UNIQUE INDEX interview_review_rounds_position
    ON interview_review_rounds (interview_review_id, round_number);

CREATE INDEX interview_review_rounds_review_idx
    ON interview_review_rounds (interview_review_id);

CREATE INDEX interview_review_rounds_type_idx
    ON interview_review_rounds (round_type);

-- ── Carry V48's flat data across ─────────────────────────────────────────────
--
-- Deliberately BEFORE the trigger exists. A review that recorded "panel, 3 rounds" has one known
-- format and three known rounds; inserting its single format as round 1 with the trigger live
-- would recompute its count down to 1 and destroy the other two. Backfilling first leaves every
-- recorded count exactly as it was.
--
-- Such a row is then partially detailed — a truthful count with detail for only its first round.
-- The alternative was inventing a format for rounds 2 and 3, which would be fabricating data.
-- The next edit through the application supplies the full list and makes it whole.

INSERT INTO interview_review_rounds (interview_review_id, round_number, round_type)
SELECT id, 1, interview_type
FROM interview_reviews
WHERE interview_type IS NOT NULL
  AND deleted_at IS NULL;

-- ── Keep the count in step with the detail, from here on ─────────────────────

CREATE OR REPLACE FUNCTION refresh_interview_round_count(p_review_id UUID) RETURNS void AS $$
BEGIN
    IF p_review_id IS NULL THEN RETURN; END IF;

    -- The review may be mid-deletion when the cascade fires this; skip rather than fail.
    IF NOT EXISTS (SELECT 1 FROM interview_reviews WHERE id = p_review_id) THEN
        RETURN;
    END IF;

    UPDATE interview_reviews ir
    SET rounds = NULLIF((SELECT COUNT(*) FROM interview_review_rounds r
                         WHERE r.interview_review_id = p_review_id), 0),
        updated_at = now()
    WHERE ir.id = p_review_id;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION interview_review_rounds_count_trigger() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM refresh_interview_round_count(OLD.interview_review_id);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.interview_review_id IS DISTINCT FROM NEW.interview_review_id THEN
        PERFORM refresh_interview_round_count(OLD.interview_review_id);
    END IF;
    PERFORM refresh_interview_round_count(NEW.interview_review_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS interview_review_rounds_count ON interview_review_rounds;

CREATE TRIGGER interview_review_rounds_count
AFTER INSERT OR DELETE OR UPDATE OF interview_review_id ON interview_review_rounds
FOR EACH ROW EXECUTE FUNCTION interview_review_rounds_count_trigger();

-- Superseded: the per-round type says everything this said, in order, without implying a process
-- has exactly one format. Dropped rather than left behind, because two ways to state the same
-- fact is how the two halves drift apart.
ALTER TABLE interview_reviews DROP COLUMN interview_type;
