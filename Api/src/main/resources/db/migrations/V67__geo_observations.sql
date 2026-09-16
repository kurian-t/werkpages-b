-- What we observed about where a submission came from, kept apart from what the person told us.
--
-- Until now those were the same three columns. GeoUtils.stampGeo fills country/state/city on a
-- request body only where the client left them blank, so a person who edits the location overwrites
-- the Cloudflare-inferred value and it is gone. That makes the one question worth asking of this
-- data - does what they claimed match what we saw - unanswerable exactly when somebody is gaming
-- the site.
--
-- One row per write, never updated. Observed location is a property of the *request*, not of the
-- manager: a manager is created by one person and edited by ten, so three columns on `managers`
-- would be last-writer-wins and would lose the same information in a new place. Append-only is the
-- only shape that can record every submission.
--
-- Nothing here is ever shown to anybody. It is evidence for abuse review and aggregate analytics,
-- and it is deliberately not reachable from any public payload.
CREATE TABLE geo_observations (
    id           BIGSERIAL PRIMARY KEY,

    -- Polymorphic on purpose, and therefore no foreign key. The tables disagree about their key
    -- type (managers use BIGINT, reviews and interview reviews use UUID), which is the same reason
    -- company_merge_records.record_id is TEXT. A foreign key would also either block deleting a
    -- manager or cascade away the audit trail at the exact moment it is worth having.
    subject_type TEXT        NOT NULL,   -- 'manager' | 'review' | 'company_review' | 'interview_review' | 'search'
    subject_id   TEXT,                   -- null for an anonymous search: there is no row yet
    action       TEXT        NOT NULL,   -- 'create' | 'edit' | 'search' | 'review'

    -- Exactly what the Cloudflare headers said, unmapped. country is the display name produced by
    -- GeoUtils.countryName so it lines up with what the app stores elsewhere; region and city are
    -- verbatim.
    country      VARCHAR(100),
    region       VARCHAR(100),
    city         VARCHAR(100),

    observed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX geo_observations_subject_idx ON geo_observations (subject_type, subject_id);
CREATE INDEX geo_observations_at_idx      ON geo_observations (observed_at DESC);

COMMENT ON TABLE geo_observations IS
    'Append-only record of the visitor location observed on each submission, from Cloudflare headers. Private: never exposed in any public API response. Compare against the declared_* columns to spot submissions whose claimed location disagrees with where the request came from.';

COMMENT ON COLUMN geo_observations.subject_id IS
    'Key of the row this observation describes, as text because the subject tables disagree on key type. Null when the submission created no row (anonymous search capture).';
