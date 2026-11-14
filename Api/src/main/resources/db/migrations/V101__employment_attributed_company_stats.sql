-- ============================================================================
-- V101: employment-attributed company statistics
--
-- Phase 5 of the Rev 268 redesign. Approved 2026-11-11 with decisions P1 and P2.
-- The contract and its invariants are in
-- /home/noragrats/b2-verification/PHASE5_STATISTICS_CONTRACT.md
--
-- ── WHAT THIS IS FOR ─────────────────────────────────────────────────────────
--
-- Two different questions were being answered by one calculation:
--
--   "who works here"                 -> affiliation.   managers.company_id
--   "what was it like to work for    -> employment.    the company the rating
--    them HERE"                                        was actually made about
--
-- The company profile's "Manager opinions" section promises the second and has
-- been showing the first. Bob received ten ratings at Adobe and then joined
-- MSN: Adobe keeps all ten, because they were experiences of working with Bob
-- at Adobe, and MSN inherits none of them. The directory can still show Bob at
-- both, because "who works here" is a different question and its answer did
-- not change.
--
-- ── WHAT THIS DOES NOT TOUCH ─────────────────────────────────────────────────
--
-- manager_count, total_reviews, avg_rating, workplace_* and interview_* keep
-- their exact definitions. No existing page changes meaning. The company
-- directory and the industry rollups are affiliation-based and stay that way.
--
-- DIVERGENCE BETWEEN THE TWO FAMILIES IS EXPECTED, NOT DRIFT. Adobe's
-- affiliation count and its employment count SHOULD differ once Bob leaves.
-- The drift detector below compares each projection against its OWN source and
-- never against the other, or it would report a correct system as broken every
-- time somebody changed job.
--
-- ── WHY THE AVERAGE IS COMPUTED DIFFERENTLY FROM THE AFFILIATION ONE ─────────
--
-- company_stats_live.avg_rating is an average of cached per-manager averages,
-- which is the right shape for "the average manager here". The employment
-- average is over INDIVIDUAL ratings, because a manager with one rating and a
-- manager with forty are not equal evidence about working at a company.
-- Asserted in the Phase 5 tests as 1.8 rather than 3.0 for the two-manager
-- case.
--
-- employment_reviews and employment_manager_count describe the SAME population,
-- deliberately: a count of ratings and a count of the distinct managers those
-- ratings are about. The profile shows both and they must not be drawn from
-- different sets.
-- ============================================================================

-- ── 1. the columns ──────────────────────────────────────────────────────────
--
-- Additive and defaulted, so an RMM insert that omits them keeps working.
-- CLAUDE.md section 10.

ALTER TABLE company_stats_live
    ADD COLUMN IF NOT EXISTS employment_reviews        BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS employment_avg_rating     NUMERIC(3,1),
    ADD COLUMN IF NOT EXISTS employment_manager_count  BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN company_stats_live.employment_reviews IS
    'Individual eligible ratings ATTRIBUTED to this company by the employment they were made '
    'about. Not SUM(managers.reviews_count), which is affiliation.';
COMMENT ON COLUMN company_stats_live.employment_avg_rating IS
    'Average over those individual ratings. Never an average of manager averages.';
COMMENT ON COLUMN company_stats_live.employment_manager_count IS
    'Distinct managers appearing in employment_reviews. The same population, so the profile can '
    'show both figures without them describing different sets.';

-- ── 2. where a rating is attributed ─────────────────────────────────────────

CREATE OR REPLACE FUNCTION rating_employment_company(
    p_attribution_basis     TEXT,
    p_claim_id              BIGINT,
    p_withdrawn_role_id     BIGINT,
    p_attributed_company_id BIGINT
) RETURNS BIGINT
LANGUAGE sql STABLE AS $$
    /*
      Switches on attribution_basis, NOT on role_basis.

      The first version of this read role_basis and inferred the rest, which gave the same answer
      for the three ordinary cases and the WRONG answer for two others. `attribution_basis` is the
      column whose entire purpose is to say where a rating's employer comes from, and the schema
      already ties the two together:

          reviews_attribution_follows_basis   claimed   -> 'role'
                                              withdrawn -> 'withdrawn_role' or 'unresolved'
                                              legacy    -> 'submitted', 'unresolved' or 'none'
          reviews_role_basis_stores_nothing   'role' means attributed_company_id IS NULL

      So:

        role            the company of the role the claim RESOLVED to, which is the employment the
                        contributor actually named. role_claims.company_id is what they typed; the
                        resolved role is what it became, and attribution follows the resolution.
        withdrawn_role  the role the rating kept. A withdrawn claim does not withdraw the
                        employment it was about.
        submitted       reviews.attributed_company_id, what the contributor STATED.
        inferred        reviews.attributed_company_id, which V102 DERIVED from the employment the
                        rating's work period falls inside. The same column and the same figure,
                        and a deliberately different basis: one is a contributor's assertion and
                        the other is this system's inference, and a statistic should never be
                        unable to say which it rests on. See V102.
        unresolved      NOTHING. We do not know which employer this was about, and guessing it
        none            from the manager's current affiliation is precisely the retroactive
                        attribution Phase 5 exists to prevent. These ratings count toward the
                        manager and toward no company's employment figure.

      Takes columns rather than a review id so it can be called for an OLD row that no longer
      exists, which is what the delete path needs.
    */
    SELECT CASE p_attribution_basis
             WHEN 'role' THEN (
                 SELECT ch.company_id FROM role_claims rc
                   JOIN career_history ch ON ch.id = rc.resolved_role_id
                  WHERE rc.id = p_claim_id)
             WHEN 'withdrawn_role' THEN (
                 SELECT ch.company_id FROM career_history ch WHERE ch.id = p_withdrawn_role_id)
             -- Both read the same column. They are separate values because the PROVENANCE
             -- differs, not because the lookup does.
             WHEN 'submitted' THEN p_attributed_company_id
             WHEN 'inferred'  THEN p_attributed_company_id
             ELSE NULL          -- 'unresolved' and 'none': no employer is known
           END;
$$;

-- ── 3. what the projection should hold ──────────────────────────────────────

CREATE OR REPLACE FUNCTION company_employment_stats_expected(
    p_company_ids BIGINT[] DEFAULT NULL
) RETURNS TABLE(company_id BIGINT, employment_reviews BIGINT,
                employment_avg_rating NUMERIC, employment_manager_count BIGINT)
LANGUAGE sql STABLE AS $$
    WITH eligible AS (
        SELECT rating_employment_company(r.attribution_basis, r.claim_id,
                                         r.withdrawn_role_id, r.attributed_company_id) AS cid,
               r.manager_id,
               r.overall_rating
          FROM reviews r
         WHERE r.deleted_at IS NULL
           -- 'live' AND publishable, not rating_is_publishable(), which is a constraint guard
           -- that answers TRUE for anything not live. Issue 14: a guard is not the eligibility
           -- question, and reading it as one counted withdrawn ratings as published.
           AND r.disposition = 'live'
           AND rating_role_is_publishable(r.id)
           -- A seed rating is a synthetic baseline, not an experience somebody had. The
           -- affiliation family excludes seed MANAGERS for the same reason.
           AND r.weight = FALSE
    )
    SELECT c.id,
           COUNT(e.cid)::BIGINT,
           ROUND(AVG(e.overall_rating)::NUMERIC, 1)::NUMERIC(3,1),
           COUNT(DISTINCT e.manager_id)::BIGINT
      FROM companies c
      -- LEFT, so a company with no attributed ratings gets an explicit zero rather than no row:
      -- "nobody has rated working here" is an answer, and the directory already shows it.
      LEFT JOIN eligible e ON e.cid = c.id
     WHERE c.status <> 'merged'
       AND (p_company_ids IS NULL OR c.id = ANY (p_company_ids))
     GROUP BY c.id;
$$;

-- ── 3b. the per-category averages, over the same population ────────────────
--
-- Not projected, because they are read on one page for one company and the
-- projection would be ten more columns maintained for a panel most visitors
-- never open. Computed here instead, from the SAME eligible, attributed
-- population as the headline figures, so the two can never describe different
-- sets of ratings.
--
-- EACH CATEGORY HAS ITS OWN DENOMINATOR, which AVG gives for free: a rating
-- that answered eight of the ten categories contributes to those eight and is
-- absent from the other two, rather than counting as a zero or dragging an
-- average toward a value nobody submitted. A category nobody answered is NULL,
-- and the caller omits it rather than publishing a null.

CREATE OR REPLACE FUNCTION company_employment_category_averages(p_company_id BIGINT)
RETURNS TABLE(
    communication_style                NUMERIC,
    perceived_approachability          NUMERIC,
    perceived_clarity_of_expectations  NUMERIC,
    feedback_style                     NUMERIC,
    perceived_supportiveness           NUMERIC,
    decision_making_style              NUMERIC,
    organization_and_planning_style    NUMERIC,
    delegation_style                   NUMERIC,
    perceived_professional_demeanor    NUMERIC,
    overall_working_experience         NUMERIC,
    rating_count                       BIGINT
) LANGUAGE sql STABLE AS $$
    SELECT ROUND(AVG(r.communication_style)::NUMERIC, 1),
           ROUND(AVG(r.perceived_approachability)::NUMERIC, 1),
           ROUND(AVG(r.perceived_clarity_of_expectations)::NUMERIC, 1),
           ROUND(AVG(r.feedback_style)::NUMERIC, 1),
           ROUND(AVG(r.perceived_supportiveness)::NUMERIC, 1),
           ROUND(AVG(r.decision_making_style)::NUMERIC, 1),
           ROUND(AVG(r.organization_and_planning_style)::NUMERIC, 1),
           ROUND(AVG(r.delegation_style)::NUMERIC, 1),
           ROUND(AVG(r.perceived_professional_demeanor)::NUMERIC, 1),
           ROUND(AVG(r.overall_working_experience)::NUMERIC, 1),
           COUNT(*)::BIGINT
      FROM reviews r
     WHERE r.deleted_at IS NULL
       AND r.disposition = 'live'
       AND rating_role_is_publishable(r.id)
       AND r.weight = FALSE
       AND rating_employment_company(r.attribution_basis, r.claim_id,
                                     r.withdrawn_role_id, r.attributed_company_id) = p_company_id;
$$;

COMMENT ON FUNCTION company_employment_category_averages(BIGINT) IS
    'Per-category averages over the same population as company_stats_live.employment_reviews. '
    'rating_count is that population size, so a caller can assert the two agree.';

-- ── 4. writing it ───────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION company_employment_stats_apply(
    p_company_ids BIGINT[] DEFAULT NULL
) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_ids     BIGINT[];
    v_id      BIGINT;
    v_written BIGINT := 0;
BEGIN
    IF p_company_ids IS NULL THEN
        /*
          A FULL REBUILD IS ONE PASS, not one pass per company.

          This used to collect every company id and loop, calling
          company_employment_stats_expected(ARRAY[id]) once each - and that function's `eligible`
          CTE scans `reviews` every time it is called. So the cost was O(companies x ratings), and
          the POPULATED rehearsal measured it: 111 SECONDS for 200 companies and 17,100 ratings,
          against 671 ms for all of V97. At production scale that is hours of held locks, and an
          empty-database rehearsal reports it as instant.

          One UPDATE joined to one evaluation of the expectation instead. The advisory lock is not
          taken here: a rebuild is not an incremental maintenance step racing a writer, it is a
          deliberate operator action, and taking several thousand locks in one transaction to
          serialise against a per-company maintainer would be its own hazard.
        */
        UPDATE company_stats_live csl
           SET employment_reviews       = e.employment_reviews,
               employment_avg_rating    = e.employment_avg_rating,
               employment_manager_count = e.employment_manager_count,
               updated_at               = now()
          FROM company_employment_stats_expected() e
         WHERE csl.company_id = e.company_id
           AND (csl.employment_reviews       IS DISTINCT FROM e.employment_reviews
             OR csl.employment_avg_rating    IS DISTINCT FROM e.employment_avg_rating
             OR csl.employment_manager_count IS DISTINCT FROM e.employment_manager_count);
        v_written := v_written + (SELECT count(*) FROM company_stats_live);

        /*
          And the rows the expectation does not cover: a company that has been merged away since
          its projection row was written. `expected` filters those out, so the UPDATE above cannot
          reach them, and leaving them would leave a figure nothing points at any more.
        */
        UPDATE company_stats_live
           SET employment_reviews = 0, employment_avg_rating = NULL,
               employment_manager_count = 0, updated_at = now()
         WHERE company_id IN (SELECT id FROM companies WHERE status = 'merged')
           AND (employment_reviews <> 0 OR employment_avg_rating IS NOT NULL
             OR employment_manager_count <> 0);

        RETURN v_written;
    ELSE
        -- Deduplicated and ascending: dedup stops a bulk statement recomputing one company once
        -- per affected row, and a single order gives every caller the same lock sequence.
        SELECT array_agg(DISTINCT x ORDER BY x) INTO v_ids
        FROM unnest(p_company_ids) AS x WHERE x IS NOT NULL;
    END IF;

    IF v_ids IS NULL OR cardinality(v_ids) = 0 THEN RETURN 0; END IF;

    FOREACH v_id IN ARRAY v_ids LOOP
        -- BEFORE the source read, and the same key space as the affiliation family so the two
        -- maintainers of one company_stats_live row serialise against each other rather than
        -- interleaving their reads and writes.
        PERFORM pg_advisory_xact_lock(847231, (v_id % 2147483647)::INT);

        -- No company, or a merged one: nothing to project. Only the projection is touched here;
        -- maintenance never deletes a source rating.
        IF NOT EXISTS (SELECT 1 FROM companies WHERE id = v_id AND status <> 'merged') THEN
            UPDATE company_stats_live
               SET employment_reviews = 0, employment_avg_rating = NULL,
                   employment_manager_count = 0, updated_at = now()
             WHERE company_id = v_id;
            CONTINUE;
        END IF;

        -- UPDATE rather than upsert: the affiliation family owns the existence of the row, and
        -- two writers both claiming to create it is how one of them starts overwriting the
        -- other's columns with defaults.
        UPDATE company_stats_live csl
           SET employment_reviews       = e.employment_reviews,
               employment_avg_rating    = e.employment_avg_rating,
               employment_manager_count = e.employment_manager_count,
               updated_at               = now()
          FROM company_employment_stats_expected(ARRAY[v_id]) e
         WHERE csl.company_id = v_id AND e.company_id = v_id
           AND (csl.employment_reviews       IS DISTINCT FROM e.employment_reviews
             OR csl.employment_avg_rating    IS DISTINCT FROM e.employment_avg_rating
             OR csl.employment_manager_count IS DISTINCT FROM e.employment_manager_count);

        v_written := v_written + 1;
    END LOOP;

    RETURN v_written;
END;
$$;

-- ── 5. keeping it current ───────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION company_employment_stats_from_reviews()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_ids BIGINT[];
BEGIN
    /*
      Statement level with transition tables, which is the house pattern for this projection
      (V90). The company ids come from BOTH tables, because the interesting changes move a rating
      from one company to another: resolving a claim, withdrawing an employment, a merge. Only the
      new company would leave the old one's figure stale, which is the whole defect this projection
      exists to avoid.

      Eligibility is NOT filtered here. These are candidates to recompute, and the recomputation
      applies eligibility from the current state; filtering twice would mean a rating becoming
      ineligible never triggered the recomputation that removes it.
    */
    SELECT array_agg(DISTINCT cid) INTO v_ids FROM (
        SELECT rating_employment_company(attribution_basis, claim_id, withdrawn_role_id,
                                         attributed_company_id) AS cid
          FROM changed
    ) x WHERE cid IS NOT NULL;

    IF v_ids IS NOT NULL THEN PERFORM company_employment_stats_apply(v_ids); END IF;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION company_employment_stats_from_roles()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_ids BIGINT[];
BEGIN
    /*
      A claim or a career-history row changing can move or unpublish ratings without any row in
      `reviews` being touched: a claim resolving, a role being accepted or rejected, an employment
      moving company. So both are maintained from here, and both the old and the new company are
      recomputed.
    */
    SELECT array_agg(DISTINCT cid) INTO v_ids FROM (
        SELECT company_id AS cid FROM changed
    ) x WHERE cid IS NOT NULL;

    IF v_ids IS NOT NULL THEN PERFORM company_employment_stats_apply(v_ids); END IF;
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS reviews_employment_stats_ins ON reviews;
CREATE TRIGGER reviews_employment_stats_ins
    AFTER INSERT ON reviews REFERENCING NEW TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION company_employment_stats_from_reviews();

DROP TRIGGER IF EXISTS reviews_employment_stats_upd_new ON reviews;
CREATE TRIGGER reviews_employment_stats_upd_new
    AFTER UPDATE ON reviews REFERENCING NEW TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION company_employment_stats_from_reviews();

DROP TRIGGER IF EXISTS reviews_employment_stats_upd_old ON reviews;
CREATE TRIGGER reviews_employment_stats_upd_old
    AFTER UPDATE ON reviews REFERENCING OLD TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION company_employment_stats_from_reviews();

DROP TRIGGER IF EXISTS reviews_employment_stats_del ON reviews;
CREATE TRIGGER reviews_employment_stats_del
    AFTER DELETE ON reviews REFERENCING OLD TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION company_employment_stats_from_reviews();

-- BOTH tables, and the OLD one was missing in the first version of this migration.
--
-- A moderator correcting a mis-resolved claim repoints it at an employment at a DIFFERENT company,
-- and `claim_resolved_role_matches` makes the claim's company_id move with it. With only the NEW
-- table, the rating appeared at the corrected company and ALSO stayed counted at the original one:
-- one rating, two companies, which is worse than the stale figure it replaced.
--
-- A rehearsal case caught it ("a CORRECTION moves the rating, and both companies are recomputed"),
-- which is the reason that case exists.
DROP TRIGGER IF EXISTS role_claims_employment_stats_upd_new ON role_claims;
CREATE TRIGGER role_claims_employment_stats_upd_new
    AFTER UPDATE ON role_claims REFERENCING NEW TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION company_employment_stats_from_roles();

DROP TRIGGER IF EXISTS role_claims_employment_stats_upd_old ON role_claims;
CREATE TRIGGER role_claims_employment_stats_upd_old
    AFTER UPDATE ON role_claims REFERENCING OLD TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION company_employment_stats_from_roles();

DROP TRIGGER IF EXISTS role_claims_employment_stats_upd ON role_claims;

DROP TRIGGER IF EXISTS career_history_employment_stats_upd_new ON career_history;
CREATE TRIGGER career_history_employment_stats_upd_new
    AFTER UPDATE ON career_history REFERENCING NEW TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION company_employment_stats_from_roles();

DROP TRIGGER IF EXISTS career_history_employment_stats_upd_old ON career_history;
CREATE TRIGGER career_history_employment_stats_upd_old
    AFTER UPDATE ON career_history REFERENCING OLD TABLE AS changed
    FOR EACH STATEMENT EXECUTE FUNCTION company_employment_stats_from_roles();

-- ── 6. rebuilding and reconciling, which section 21 requires ────────────────

CREATE OR REPLACE FUNCTION refresh_company_employment_stats() RETURNS BIGINT
LANGUAGE sql AS $$
    -- The whole projection, from source data alone. CLAUDE.md section 21 (4).
    SELECT company_employment_stats_apply(NULL);
$$;

CREATE OR REPLACE FUNCTION company_employment_stats_detect_drift()
RETURNS TABLE(company_id BIGINT,
              stored_reviews BIGINT, expected_reviews BIGINT,
              stored_avg NUMERIC, expected_avg NUMERIC,
              stored_managers BIGINT, expected_managers BIGINT)
LANGUAGE sql STABLE AS $$
    /*
      CLAUDE.md section 21 (5). Compares the projection against ITS OWN SOURCE and against nothing
      else.

      It must never compare the employment figures with the affiliation ones. Those legitimately
      differ the moment a manager changes employer, which is the entire point of Phase 5, and a
      detector that read the difference as drift would alarm on every job change and be switched
      off within a week.
    */
    SELECT csl.company_id,
           csl.employment_reviews, e.employment_reviews,
           csl.employment_avg_rating, e.employment_avg_rating,
           csl.employment_manager_count, e.employment_manager_count
      FROM company_stats_live csl
      JOIN company_employment_stats_expected() e ON e.company_id = csl.company_id
     WHERE csl.employment_reviews       IS DISTINCT FROM e.employment_reviews
        OR csl.employment_avg_rating    IS DISTINCT FROM e.employment_avg_rating
        OR csl.employment_manager_count IS DISTINCT FROM e.employment_manager_count;
$$;

-- ── 7. backfill ─────────────────────────────────────────────────────────────
--
-- Every existing row gets its figures now, so the projection is never served
-- half-populated. Nothing here writes to reviews; a backfill that moved a
-- rating would be the retroactive attribution this phase exists to prevent.

SELECT refresh_company_employment_stats();
