-- Location statistics as an explicit, incrementally maintained read model.
--
-- Contribution tables stay the sole authority. These are projections: derived, disposable, and
-- rebuildable from source at any time. Nothing here is ever the answer to "what did somebody say" -
-- only to "what do the things people said add up to".
--
-- Deliberately not a view, and deliberately not on-the-fly aggregation with a materialized view
-- held in reserve. See CLAUDE.md section 21: the escalation path is direct query -> indexes ->
-- incrementally maintained read table, and for this feature the read table is chosen up front
-- because every company page will slice by location on every load.
--
-- ── Scope identity ──────────────────────────────────────────────────────────────────────────
--
-- One contribution counts toward every level of its location hierarchy, so a rating at
-- 1005 Ottawa St N increments five rows:
--
--   company                          scope_key = ''
--   country:CA                       scope_key = 'CA'
--   state:CA-ON                      scope_key = 'CA-ON'
--   city:CA-ON:kitchener             scope_key = 'CA-ON:kitchener'
--   place:382                        scope_key = '382'
--
-- Company-wide is itself a scope, on purpose. If "All locations" were computed by a different
-- mechanism than a filtered view, the two would eventually disagree for reasons nobody could
-- explain - and the first question asked would be why the overall number does not match the parts.
-- Same source, same projector, same arithmetic.
--
-- Codes, not display names, in scope_key: a province renamed in the UI must not orphan its stats.
--
-- ── Sums and counts, never averages ─────────────────────────────────────────────────────────
--
-- Stored as sum + count so a delta is arithmetic rather than a recomputation, and so removing one
-- contribution is exact rather than approximate. Averages are computed on read.
--
-- Manager and interview categories are nullable, so each carries its own count - SQL AVG ignores
-- NULLs and the projection must match. Company-rating categories are NOT NULL, so review_count
-- covers them all; that difference is the reason these are three tables rather than one.
--
-- ── Zero rows are kept, not deleted ─────────────────────────────────────────────────────────
--
-- When the last contribution in a scope is removed the row stays, at zero. Deleting it would race
-- with a concurrent insert re-creating it, and the bounded cost of keeping it is a handful of rows
-- per company. Readers filter on the count; a facet list must never offer a scope with none.

-- ── Manager ratings ─────────────────────────────────────────────────────────────────────────
CREATE TABLE manager_location_stats_live (
    company_id   BIGINT  NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    scope_type   TEXT    NOT NULL,   -- company | country | state | city | place
    scope_key    TEXT    NOT NULL,   -- '' for company-wide

    review_count BIGINT  NOT NULL DEFAULT 0,
    rating_sum   NUMERIC NOT NULL DEFAULT 0,

    communication_style_sum                 NUMERIC NOT NULL DEFAULT 0,
    communication_style_count               BIGINT  NOT NULL DEFAULT 0,
    perceived_approachability_sum           NUMERIC NOT NULL DEFAULT 0,
    perceived_approachability_count         BIGINT  NOT NULL DEFAULT 0,
    perceived_clarity_of_expectations_sum   NUMERIC NOT NULL DEFAULT 0,
    perceived_clarity_of_expectations_count BIGINT  NOT NULL DEFAULT 0,
    feedback_style_sum                      NUMERIC NOT NULL DEFAULT 0,
    feedback_style_count                    BIGINT  NOT NULL DEFAULT 0,
    perceived_supportiveness_sum            NUMERIC NOT NULL DEFAULT 0,
    perceived_supportiveness_count          BIGINT  NOT NULL DEFAULT 0,
    decision_making_style_sum               NUMERIC NOT NULL DEFAULT 0,
    decision_making_style_count             BIGINT  NOT NULL DEFAULT 0,
    organization_and_planning_style_sum     NUMERIC NOT NULL DEFAULT 0,
    organization_and_planning_style_count   BIGINT  NOT NULL DEFAULT 0,
    delegation_style_sum                    NUMERIC NOT NULL DEFAULT 0,
    delegation_style_count                  BIGINT  NOT NULL DEFAULT 0,
    perceived_professional_demeanor_sum     NUMERIC NOT NULL DEFAULT 0,
    perceived_professional_demeanor_count   BIGINT  NOT NULL DEFAULT 0,
    overall_working_experience_sum          NUMERIC NOT NULL DEFAULT 0,
    overall_working_experience_count        BIGINT  NOT NULL DEFAULT 0,

    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (company_id, scope_type, scope_key)
);

-- ── Workplace ratings ───────────────────────────────────────────────────────────────────────
CREATE TABLE company_location_stats_live (
    company_id   BIGINT  NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    scope_type   TEXT    NOT NULL,
    scope_key    TEXT    NOT NULL,

    review_count BIGINT  NOT NULL DEFAULT 0,
    rating_sum   NUMERIC NOT NULL DEFAULT 0,

    -- Every category here is NOT NULL on company_reviews, so review_count is their count too.
    work_life_balance_sum       NUMERIC NOT NULL DEFAULT 0,
    compensation_benefits_sum   NUMERIC NOT NULL DEFAULT 0,
    career_growth_sum           NUMERIC NOT NULL DEFAULT 0,
    job_security_sum            NUMERIC NOT NULL DEFAULT 0,
    workload_sustainability_sum NUMERIC NOT NULL DEFAULT 0,
    senior_leadership_sum       NUMERIC NOT NULL DEFAULT 0,
    company_communication_sum   NUMERIC NOT NULL DEFAULT 0,
    flexibility_sum             NUMERIC NOT NULL DEFAULT 0,
    inclusion_belonging_sum     NUMERIC NOT NULL DEFAULT 0,
    tools_resources_sum         NUMERIC NOT NULL DEFAULT 0,

    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (company_id, scope_type, scope_key)
);

-- ── Interview experiences ───────────────────────────────────────────────────────────────────
CREATE TABLE interview_location_stats_live (
    company_id       BIGINT  NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    scope_type       TEXT    NOT NULL,
    scope_key        TEXT    NOT NULL,

    experience_count BIGINT  NOT NULL DEFAULT 0,
    rating_sum       NUMERIC NOT NULL DEFAULT 0,

    communication_sum            NUMERIC NOT NULL DEFAULT 0,
    communication_count          BIGINT  NOT NULL DEFAULT 0,
    respect_for_time_sum         NUMERIC NOT NULL DEFAULT 0,
    respect_for_time_count       BIGINT  NOT NULL DEFAULT 0,
    role_clarity_sum             NUMERIC NOT NULL DEFAULT 0,
    role_clarity_count           BIGINT  NOT NULL DEFAULT 0,
    process_fairness_sum         NUMERIC NOT NULL DEFAULT 0,
    process_fairness_count       BIGINT  NOT NULL DEFAULT 0,
    next_step_transparency_sum   NUMERIC NOT NULL DEFAULT 0,
    next_step_transparency_count BIGINT  NOT NULL DEFAULT 0,
    difficulty_sum               NUMERIC NOT NULL DEFAULT 0,
    difficulty_count             BIGINT  NOT NULL DEFAULT 0,

    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (company_id, scope_type, scope_key)
);

-- ── Read paths ──────────────────────────────────────────────────────────────────────────────
--
-- The primary key already serves the point lookup a delta needs. These serve the facet list: every
-- scope of one type for one company, ordered by how much is behind it.
CREATE INDEX manager_location_stats_facets
    ON manager_location_stats_live (company_id, scope_type, review_count DESC)
    WHERE review_count > 0;
CREATE INDEX company_location_stats_facets
    ON company_location_stats_live (company_id, scope_type, review_count DESC)
    WHERE review_count > 0;
CREATE INDEX interview_location_stats_facets
    ON interview_location_stats_live (company_id, scope_type, experience_count DESC)
    WHERE experience_count > 0;

-- ── Rebuild support ─────────────────────────────────────────────────────────────────────────
--
-- A rebuild recomputes everything from source into these staging tables, validates the result, and
-- only then replaces the live contents inside one transaction. The live tables are never emptied
-- speculatively - a rebuild that fails halfway leaves the old projection serving.
--
-- Staging rather than a generation column: a generation would have to be carried by the primary key
-- and consulted by every increment on the hot path, to make a rebuild - a rare, offline operation -
-- marginally cheaper. The cost belongs on the rare side.
CREATE TABLE manager_location_stats_rebuild   (LIKE manager_location_stats_live   INCLUDING ALL);
CREATE TABLE company_location_stats_rebuild   (LIKE company_location_stats_live   INCLUDING ALL);
CREATE TABLE interview_location_stats_rebuild (LIKE interview_location_stats_live INCLUDING ALL);

-- ── Source-side indexes the rebuild depends on ──────────────────────────────────────────────
--
-- A rebuild scans every contribution grouped by company. Without these it is a sequential scan of
-- the whole table per company, which is what makes people stop running rebuilds.
CREATE INDEX IF NOT EXISTS reviews_manager_id_idx           ON reviews (manager_id);
CREATE INDEX IF NOT EXISTS company_reviews_company_id_idx   ON company_reviews (company_id);
CREATE INDEX IF NOT EXISTS interview_reviews_company_id_idx ON interview_reviews (company_id);

COMMENT ON TABLE manager_location_stats_live IS
    'Derived projection of manager ratings by location scope. Never authoritative - rebuildable from reviews alone. Maintained transactionally by LocationStatsProjector; see docs/location-model.md.';
COMMENT ON COLUMN manager_location_stats_live.scope_key IS
    'Empty string for company-wide; ISO codes otherwise (CA, CA-ON, CA-ON:kitchener) or the company_locations id for place. Codes rather than display names so a rename cannot orphan a row.';
