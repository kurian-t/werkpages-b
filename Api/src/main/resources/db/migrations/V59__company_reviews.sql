-- What a company is like to work for, as distinct from what its managers are like.
--
-- Until now a company page could only answer the second question, and the tab that claimed to say
-- "what it's like to work at X" was in fact showing averages computed from manager reviews. That
-- is a different question wearing the wrong label: a company with poor pay, no progression and no
-- job security can be full of decent managers, and the platform had no way to say so.
--
-- Deliberately its own table rather than columns on reviews. A manager rating and a company
-- rating are separate contributions with separate lifecycles: someone can edit one without
-- touching the other, delete one and keep the other, and rate several managers at one employer
-- while holding a single opinion of the employer itself.
--
-- The ten categories below are chosen to be things one manager does not control. Anything a
-- manager decides belongs in reviews.category ratings, not here - "role clarity" was considered
-- and dropped for exactly that reason, being all but identical to the manager category
-- perceived_clarity_of_expectations.

CREATE TABLE company_reviews (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    company_id      BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    user_id         UUID   NOT NULL REFERENCES users(id)     ON DELETE CASCADE,

    -- ── Overall ──────────────────────────────────────────────────────────────
    -- Asked explicitly rather than derived from the ten below. Someone's summary judgement is not
    -- the mean of its parts: "the pay was poor and it was chaotic, but I loved working there" is
    -- a real and common position, and averaging the categories erases exactly that.
    overall_rating           NUMERIC(2,1) NOT NULL CHECK (overall_rating BETWEEN 0 AND 5),

    -- ── The ten ──────────────────────────────────────────────────────────────
    -- All NOT NULL. There is no N/A: a corpus where half the ratings skipped career growth cannot
    -- be sliced by career growth, which is the same reasoning the interview form already applies
    -- to its own categories.
    work_life_balance        NUMERIC(2,1) NOT NULL CHECK (work_life_balance        BETWEEN 0 AND 5),
    compensation_benefits    NUMERIC(2,1) NOT NULL CHECK (compensation_benefits    BETWEEN 0 AND 5),
    career_growth            NUMERIC(2,1) NOT NULL CHECK (career_growth            BETWEEN 0 AND 5),
    job_security             NUMERIC(2,1) NOT NULL CHECK (job_security             BETWEEN 0 AND 5),
    workload_sustainability  NUMERIC(2,1) NOT NULL CHECK (workload_sustainability  BETWEEN 0 AND 5),
    senior_leadership        NUMERIC(2,1) NOT NULL CHECK (senior_leadership        BETWEEN 0 AND 5),
    company_communication    NUMERIC(2,1) NOT NULL CHECK (company_communication    BETWEEN 0 AND 5),
    flexibility              NUMERIC(2,1) NOT NULL CHECK (flexibility              BETWEEN 0 AND 5),
    inclusion_belonging      NUMERIC(2,1) NOT NULL CHECK (inclusion_belonging      BETWEEN 0 AND 5),
    tools_resources          NUMERIC(2,1) NOT NULL CHECK (tools_resources          BETWEEN 0 AND 5),

    -- ── When ─────────────────────────────────────────────────────────────────
    -- A rating of an employer in 2019 says little about it now, so the period is data rather than
    -- metadata. worked_until NULL means "still there".
    worked_from     DATE NOT NULL,
    worked_until    DATE,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT company_reviews_period_ordered
        CHECK (worked_until IS NULL OR worked_until >= worked_from)
);

-- One rating per person per company.
--
-- Per company row, not per corporate group: Zehrs and Loblaw are two companies, and someone who
-- worked at both has two distinct experiences to report. Rating Zehrs twice is what this forbids.
--
-- Partial on deleted_at so a withdrawn rating does not permanently bar the person from rating
-- that employer again - the same shape as the interview index above.
CREATE UNIQUE INDEX company_reviews_one_per_user_company
    ON company_reviews (user_id, company_id)
    WHERE deleted_at IS NULL;

CREATE INDEX company_reviews_company_idx ON company_reviews (company_id) WHERE deleted_at IS NULL;

COMMENT ON TABLE company_reviews IS
    'What a company is like to work for. Never manager quality - that lives in reviews. The two '
    'are shown side by side on a company page precisely so they can disagree.';
