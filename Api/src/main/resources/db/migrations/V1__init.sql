CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
	id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    auth0_id 	TEXT UNIQUE NOT NULL,
    email 		TEXT UNIQUE NOT NULL,
    username 	TEXT UNIQUE NOT NULL,
    first_name 	TEXT,
    last_name 	TEXT,
    created_at 	TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE managers (
    id              BIGSERIAL PRIMARY KEY,
    external_id     TEXT UNIQUE,

    name            TEXT NOT NULL,
    company         TEXT NOT NULL,
    title           TEXT NOT NULL,
    image           TEXT,

    overall_rating  NUMERIC(2,1) CHECK (overall_rating BETWEEN 0 AND 5),
    reviews_count   INTEGER NOT NULL DEFAULT 0,

    bio             TEXT,
    status          TEXT NOT NULL CHECK (status IN ('active', 'retired')),
    linkedin_url    TEXT,

    category_averages JSONB,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE career_history (
    id          BIGSERIAL PRIMARY KEY,
    manager_id  BIGINT NOT NULL REFERENCES managers(id) ON DELETE CASCADE,

    company     TEXT NOT NULL,
    title       TEXT NOT NULL,
    start_date  TIMESTAMPTZ NOT NULL,
    end_date    TIMESTAMPTZ,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX idx_career_history_manager_id ON career_history(manager_id);

CREATE TABLE reviews (
    id 				UUID PRIMARY KEY DEFAULT uuid_generate_v4(),

    manager_id      BIGINT NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    author          TEXT NOT NULL, -- snapshot of display name at time of review

    overall_rating  NUMERIC(2,1) NOT NULL CHECK (overall_rating BETWEEN 0 AND 5),

    -- Category ratings
    communication_style                     NUMERIC(2,1) CHECK (communication_style BETWEEN 0 AND 5),
    perceived_approachability               NUMERIC(2,1) CHECK (perceived_approachability BETWEEN 0 AND 5),
    perceived_clarity_of_expectations       NUMERIC(2,1) CHECK (perceived_clarity_of_expectations BETWEEN 0 AND 5),
    feedback_style                          NUMERIC(2,1) CHECK (feedback_style BETWEEN 0 AND 5),
    perceived_supportiveness                NUMERIC(2,1) CHECK (perceived_supportiveness BETWEEN 0 AND 5),
    decision_making_style                   NUMERIC(2,1) CHECK (decision_making_style BETWEEN 0 AND 5),
    organization_and_planning_style         NUMERIC(2,1) CHECK (organization_and_planning_style BETWEEN 0 AND 5),
    delegation_style                        NUMERIC(2,1) CHECK (delegation_style BETWEEN 0 AND 5),
    perceived_professional_demeanor         NUMERIC(2,1) CHECK (perceived_professional_demeanor BETWEEN 0 AND 5),
    overall_working_experience              NUMERIC(2,1) CHECK (overall_working_experience BETWEEN 0 AND 5),

    manager_company TEXT NOT NULL, -- snapshot
    manager_title   TEXT NOT NULL, -- snapshot

    text            TEXT,
    verified        BOOLEAN NOT NULL DEFAULT false,
    helpful_count   INTEGER NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reviews_manager_id ON reviews(manager_id);
CREATE INDEX idx_reviews_created_at ON reviews(created_at DESC);

CREATE TABLE reports (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    manager_id  BIGINT NOT NULL REFERENCES managers(id) ON DELETE CASCADE,
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    reason      TEXT NOT NULL CHECK (reason IN (
                    'incorrect_person',
                    'never_worked_here',
                    'duplicate_profile',
                    'incorrect_information',
                    'other'
                )),
    comment     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

