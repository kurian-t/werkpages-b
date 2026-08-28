-- Interview experience reviews.
--
-- These attach to a COMPANY, not a manager: the audience is people who have never worked there
-- and therefore have no reason to care about manager ratings yet. Structured only, no free text —
-- an interview review naming an interviewer is a defamation surface with no employment
-- relationship behind it, and structured-only keeps every response comparable.
--
-- Six rating categories, deliberately fewer than the ten on a manager review. A manager review
-- is earned over months; an interview is three conversations, and a long form after a rejection
-- does not get filled in.

CREATE TABLE interview_reviews (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    company_id      BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    user_id         UUID   NOT NULL REFERENCES users(id)     ON DELETE CASCADE,

    -- ── Ratings ──────────────────────────────────────────────────────────────
    overall_rating          NUMERIC(2,1) NOT NULL CHECK (overall_rating BETWEEN 0 AND 5),
    communication           NUMERIC(2,1) CHECK (communication          BETWEEN 0 AND 5),
    respect_for_time        NUMERIC(2,1) CHECK (respect_for_time       BETWEEN 0 AND 5),
    role_clarity            NUMERIC(2,1) CHECK (role_clarity           BETWEEN 0 AND 5),
    process_fairness        NUMERIC(2,1) CHECK (process_fairness       BETWEEN 0 AND 5),
    next_step_transparency  NUMERIC(2,1) CHECK (next_step_transparency BETWEEN 0 AND 5),

    -- Difficulty is NOT a quality score. 5 = very difficult, which is not the same as bad, so it
    -- is deliberately excluded from overall_rating and from any "top rated" logic.
    difficulty      SMALLINT CHECK (difficulty BETWEEN 1 AND 5),

    -- ── Outcome ──────────────────────────────────────────────────────────────
    -- Required, because rejected candidates rate the process markedly lower than hired ones.
    -- Without this the average is just a measure of who was most annoyed; with it the company
    -- page can show the split, which is both more honest and more useful to a candidate.
    outcome         TEXT NOT NULL CHECK (outcome IN ('offer','no_offer','withdrew','pending')),

    -- ── Structured facts ─────────────────────────────────────────────────────
    interview_type  TEXT     CHECK (interview_type IN ('phone','video','onsite','technical','panel')),
    rounds          SMALLINT CHECK (rounds BETWEEN 1 AND 10),
    process_length  TEXT     CHECK (process_length IN ('under_1_week','1_2_weeks','2_4_weeks','over_1_month')),
    role_category   TEXT,
    -- Interview processes track the hiring market closely; a 2019 process says little about now.
    -- Year is a display and filtering axis, not metadata.
    interview_year  SMALLINT NOT NULL CHECK (interview_year BETWEEN 2000 AND 2100),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

-- One review per person per company per year. Anyone can claim to have interviewed anywhere —
-- there is no employment to verify — so this is the cheapest meaningful brake on fabrication.
CREATE UNIQUE INDEX interview_reviews_one_per_user_company_year
    ON interview_reviews (user_id, company_id, interview_year)
    WHERE deleted_at IS NULL;

CREATE INDEX interview_reviews_company_idx ON interview_reviews (company_id) WHERE deleted_at IS NULL;
CREATE INDEX interview_reviews_outcome_idx ON interview_reviews (company_id, outcome) WHERE deleted_at IS NULL;
CREATE INDEX interview_reviews_year_idx    ON interview_reviews (company_id, interview_year) WHERE deleted_at IS NULL;

-- ── Cached per-company aggregates ────────────────────────────────────────────
-- Split by outcome so the company page can show "4.6 from offers, 3.7 from rejections" without
-- recomputing on every request. Maintained by trigger rather than application code: two
-- codebases share this database, and company_stats_live already drifted once when a caller
-- forgot to refresh it (see V47).
CREATE TABLE company_interview_stats (
    company_id      BIGINT PRIMARY KEY REFERENCES companies(id) ON DELETE CASCADE,
    review_count    INTEGER      NOT NULL DEFAULT 0,
    avg_rating      NUMERIC(2,1),
    avg_difficulty  NUMERIC(2,1),
    offer_count     INTEGER      NOT NULL DEFAULT 0,
    offer_avg       NUMERIC(2,1),
    no_offer_count  INTEGER      NOT NULL DEFAULT 0,
    no_offer_avg    NUMERIC(2,1),
    median_rounds   SMALLINT,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION refresh_company_interview_stats(p_company_id BIGINT) RETURNS void AS $$
BEGIN
    IF p_company_id IS NULL THEN RETURN; END IF;

    -- Deleting a company cascades into interview_reviews, which fires the trigger below while the
    -- company row is already gone. Without this guard the refresh would try to INSERT a stats row
    -- referencing a company that no longer exists and fail on its own foreign key, taking the
    -- company deletion down with it. The cascade on company_interview_stats removes the row.
    IF NOT EXISTS (SELECT 1 FROM companies WHERE id = p_company_id) THEN
        RETURN;
    END IF;

    INSERT INTO company_interview_stats (
        company_id, review_count, avg_rating, avg_difficulty,
        offer_count, offer_avg, no_offer_count, no_offer_avg, median_rounds, updated_at)
    SELECT p_company_id,
           COUNT(*),
           ROUND(AVG(overall_rating)::NUMERIC, 1),
           ROUND(AVG(difficulty)::NUMERIC, 1),
           COUNT(*) FILTER (WHERE outcome = 'offer'),
           ROUND(AVG(overall_rating) FILTER (WHERE outcome = 'offer')::NUMERIC, 1),
           COUNT(*) FILTER (WHERE outcome = 'no_offer'),
           ROUND(AVG(overall_rating) FILTER (WHERE outcome = 'no_offer')::NUMERIC, 1),
           PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY rounds),
           now()
    FROM interview_reviews
    WHERE company_id = p_company_id AND deleted_at IS NULL
    ON CONFLICT (company_id) DO UPDATE SET
        review_count   = EXCLUDED.review_count,
        avg_rating     = EXCLUDED.avg_rating,
        avg_difficulty = EXCLUDED.avg_difficulty,
        offer_count    = EXCLUDED.offer_count,
        offer_avg      = EXCLUDED.offer_avg,
        no_offer_count = EXCLUDED.no_offer_count,
        no_offer_avg   = EXCLUDED.no_offer_avg,
        median_rounds  = EXCLUDED.median_rounds,
        updated_at     = now();

    -- The aggregate above always produces a row (COUNT(*) of nothing is 0), so unlike
    -- company_stats_live there is no orphan case — but a company whose last review is
    -- soft-deleted should not keep a stale row claiming reviews exist.
    DELETE FROM company_interview_stats
    WHERE company_id = p_company_id AND review_count = 0;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION interview_reviews_stats_trigger() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM refresh_company_interview_stats(OLD.company_id);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.company_id IS DISTINCT FROM NEW.company_id THEN
        PERFORM refresh_company_interview_stats(OLD.company_id);
    END IF;
    PERFORM refresh_company_interview_stats(NEW.company_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS interview_reviews_stats ON interview_reviews;

CREATE TRIGGER interview_reviews_stats
AFTER INSERT OR DELETE OR UPDATE OF company_id, overall_rating, difficulty, outcome, rounds, deleted_at
ON interview_reviews
FOR EACH ROW EXECUTE FUNCTION interview_reviews_stats_trigger();
