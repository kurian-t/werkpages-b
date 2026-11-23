-- ============================================================================
-- V113: COMPANY AVERAGES COUNT EVERY RATING
--
-- Two separate defects, both of which make a company's average rating wrong,
-- both reproduced on a V112 database before this was written.
--
-- ── DEFECT 1: A RATING WRITTEN TODAY BELONGS TO NO COMPANY ──────────────────
--
-- `reviews.attribution_basis` defaults to 'none', and nothing in the live
-- submission path sets anything else. RoleAwareSubmissionService does, and it
-- is behind ROLE_AWARE_SUBMISSION_ENABLED, which is off. So the ONLY rows that
-- have ever carried an employer are the ones V102 backfilled when it ran.
--
-- V102 was a one-off. Every rating submitted after it counts toward its manager
-- and toward no company, because `rating_employment_company` answers NULL for
-- basis 'none' - correctly, since nothing told it an employer.
--
-- The company profile's "Manager opinions" section reads exactly those
-- employment-attributed figures, so its average is computed from whichever
-- subset of a company's ratings V102 happened to reach. Reproduced on a fresh
-- V112 database: two live contributor ratings, 1.0 and 5.0, on two managers at
-- one company, gave employment_reviews = 0 and employment_avg_rating = NULL.
-- With one older attributed rating of 1.0 beside them, the page shows 1.0 while
-- the ratings on screen are 1 and 5.
--
-- ── DEFECT 2: THE AFFILIATION AVERAGE IS A MEAN OF MEANS ────────────────────
--
-- `company_stats_expected.avg_rating` is AVG(m.overall_rating): the unweighted
-- average of each manager's cached average. A manager with one rating counts as
-- much as a manager with forty.
--
-- Reproduced on the same database: twelve live ratings at one company - one 1.0,
-- one 5.0 and ten 5.0 - reported 3.7. The average of the twelve ratings is 4.67.
--
-- This is the figure on company cards, in the directory listing and in the
-- company header, and it is the one a reader compares between employers.
--
-- ── WHAT THIS MIGRATION DOES ────────────────────────────────────────────────
--
--   1. attributes a legacy rating to an employer AS IT IS WRITTEN, by the same
--      evidence V102 used, with one rule added that is only available at insert;
--   2. re-runs V102's two rules for the ratings that have arrived since it ran;
--   3. makes the affiliation average weight each manager by how many ratings
--      they actually have;
--   4. rebuilds both projections so the existing rows are corrected, which is
--      the point of the exercise: the figures on screen today are wrong.
--
-- ── RMM COMPATIBILITY ───────────────────────────────────────────────────────
--
-- Nothing is added, dropped, renamed or narrowed. No column changes type or
-- nullability, and no constraint becomes stricter. RateMyManagers writes
-- `reviews` through the same legacy path and gets the same attribution, without
-- knowing this trigger exists - which is the point of putting it in the
-- database rather than in one of the two backends.
-- ============================================================================


-- ── 1. ATTRIBUTION AT INSERT ────────────────────────────────────────────────
--
-- THE SAME EVIDENCE V102 USED, AND ONE RULE IT COULD NOT USE.
--
-- V102's rules, in its own order:
--
--   1. the rating's work period overlaps exactly one accepted employment -> that
--      employment's company;
--   2. no usable period, but the manager has exactly one accepted employer ever
--      -> that one, because there is no other employer it could be about;
--   3. otherwise 'none'. Ambiguity is not attributed.
--
-- Rule 1 here counts DISTINCT COMPANIES rather than overlapping rows. V102
-- counted rows and left rule 2 to pick up a rating that overlapped two
-- employments at the SAME employer; counting companies settles that case where
-- it arises, and settles it the same way. Ambiguity is still ambiguity: two
-- overlapping employments at DIFFERENT companies attribute nothing.
--
-- ── THE RULE V102 COULD NOT USE, AND WHY IT IS AVAILABLE HERE ───────────────
--
-- V102 refused `reviews.manager_company`, and its reasoning is worth repeating
-- because this migration appears to contradict it. Six code paths rewrite that
-- column, and one is fatal to a backfill:
--
--     ManagerRepository, when a manager's company is EDITED:
--         UPDATE reviews SET manager_company = $1
--          WHERE manager_id = $2 AND manager_company = $3
--
-- So by the time a backfill reads that column, a manager who moved from Adobe to
-- MSN has had every Adobe rating relabelled MSN. Attributing from it RETROSPECTIVELY
-- moves historical ratings to an employer they were never about.
--
-- At INSERT it is a different value. It is what the contributor has just stated,
-- before anything has had the chance to rewrite it, and "the contributor named
-- the employer" is precisely what attribution_basis = 'submitted' means. The
-- hazard is reading it later, not reading it now, and this trigger only ever
-- reads it now.
--
-- It is still only reached when neither employment rule settles the question,
-- so an employment record always wins over a typed name.
--
-- ── WHAT IS DELIBERATELY NOT DONE ───────────────────────────────────────────
--
-- The manager's CURRENT company_id is not used as a fallback. It is not evidence
-- about this rating: it is where the directory says they work now, which is the
-- retroactive attribution Phase 5 exists to prevent. A rating that resolves to
-- nothing stays 'none' and counts toward its manager only, exactly as before.
--
-- A platform example is skipped. It is a demonstration, not an experience
-- somebody had, and company_employment_stats_from_reviews excludes weighted rows
-- anyway - attributing one would record an employer for a rating no statistic
-- will ever read.

CREATE OR REPLACE FUNCTION attribute_legacy_rating()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_companies INT;
    v_company   BIGINT;
BEGIN
    -- Only a legacy rating that nothing has attributed. A claimed or withdrawn
    -- rating gets its employer from its claim or its kept role, and
    -- reviews_attribution_follows_basis refuses anything else for those.
    IF NEW.role_basis <> 'legacy'
       OR NEW.attribution_basis <> 'none'
       OR NEW.attributed_company_id IS NOT NULL
       OR NEW.claim_id IS NOT NULL
       OR NEW.weight THEN
        RETURN NEW;
    END IF;

    -- Rule 1: the employment the rating's own work period falls inside.
    IF NEW.worked_from IS NOT NULL THEN
        SELECT count(DISTINCT ch.company_id), min(ch.company_id)
          INTO v_companies, v_company
          FROM career_history ch
          JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
         WHERE ch.manager_id = NEW.manager_id
           AND ch.state = 'accepted'
           AND ch.company_id IS NOT NULL
           -- Calendar days on both sides. V97 made start_day and end_day generated
           -- DATE columns precisely so this never depends on the session timezone.
           AND COALESCE(ch.start_day, '-infinity'::date)
               <= COALESCE(NEW.worked_until, 'infinity'::date)
           AND COALESCE(ch.end_day, 'infinity'::date) >= NEW.worked_from;

        IF v_companies = 1 THEN
            NEW.attributed_company_id := v_company;
            NEW.attribution_basis     := 'inferred';
            RETURN NEW;
        END IF;
    END IF;

    -- Rule 2: a manager with exactly one employer ever leaves no room for doubt.
    SELECT count(DISTINCT ch.company_id), min(ch.company_id)
      INTO v_companies, v_company
      FROM career_history ch
      JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
     WHERE ch.manager_id = NEW.manager_id
       AND ch.state = 'accepted'
       AND ch.company_id IS NOT NULL;

    IF v_companies = 1 THEN
        NEW.attributed_company_id := v_company;
        NEW.attribution_basis     := 'inferred';
        RETURN NEW;
    END IF;

    -- Rule 3: the employer the contributor has just named, resolved to the row we
    -- hold for it. Matched on the same normalised key as companies_name_ci, so
    -- "Acme " and "acme" find the one company rather than missing it.
    IF NEW.manager_company IS NOT NULL AND btrim(NEW.manager_company) <> '' THEN
        SELECT count(*), min(c.id)
          INTO v_companies, v_company
          FROM companies c
         WHERE c.status <> 'merged'
           AND lower(btrim(c.name)) = lower(btrim(NEW.manager_company));

        IF v_companies = 1 THEN
            NEW.attributed_company_id := v_company;
            NEW.attribution_basis     := 'submitted';
            RETURN NEW;
        END IF;
    END IF;

    -- Nothing settled it. 'none' stands: this rating counts toward its manager
    -- and toward no company, which is an answer the projection already handles.
    RETURN NEW;
END $$;

COMMENT ON FUNCTION attribute_legacy_rating() IS
    'Attributes a legacy rating to an employer as it is written, by V102''s rules plus the '
    'company the contributor named. Without it attribution_basis stays ''none'' and the rating '
    'counts toward no company''s employment statistics.';

DROP TRIGGER IF EXISTS reviews_attribute_employer ON reviews;
CREATE TRIGGER reviews_attribute_employer
BEFORE INSERT ON reviews
FOR EACH ROW EXECUTE FUNCTION attribute_legacy_rating();


-- ── 2. THE RATINGS THAT ARRIVED AFTER V102 ──────────────────────────────────
--
-- Rules 1 and 2 only. Rule 3 is an INSERT rule and must not be used here, for
-- the reason stated above: by now manager_company may have been rewritten by a
-- profile edit, and a backfill reading it would move historical ratings onto an
-- employer they were never about. That is the exact defect V102 avoided and this
-- migration will not reintroduce.
--
-- Ratings that neither rule settles stay 'none'. The count is reported below
-- rather than left silent, because "we could not tell" is a real answer and the
-- figure beside it is the honest size of what the company pages cannot show.
--
-- The projection's incremental triggers are off for the backfill and the whole
-- projection is rebuilt once at the end. V102 measured 161,645 ms with them on,
-- against 554 ms for the set-based rebuild. DISABLE TRIGGER is transactional, so
-- a rollback restores them without an operator having to remember.

ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_ins;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_upd_new;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_upd_old;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_del;

WITH candidate AS (
    SELECT r.id AS review_id,
           min(ch.company_id)              AS company_id,
           count(DISTINCT ch.company_id)   AS companies
      FROM reviews r
      JOIN career_history ch
        ON ch.manager_id = r.manager_id
       AND ch.state = 'accepted'
       AND ch.company_id IS NOT NULL
       AND COALESCE(ch.start_day, '-infinity'::date) <= COALESCE(r.worked_until, 'infinity'::date)
       AND COALESCE(ch.end_day,   'infinity'::date)  >= r.worked_from
      JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
     WHERE r.attribution_basis = 'none'
       AND r.role_basis = 'legacy'
       AND NOT r.weight
       AND r.worked_from IS NOT NULL
     GROUP BY r.id
)
UPDATE reviews r
   SET attributed_company_id = candidate.company_id,
       attribution_basis     = 'inferred'
  FROM candidate
 WHERE r.id = candidate.review_id
   AND candidate.companies = 1;

WITH sole_employer AS (
    SELECT ch.manager_id, min(ch.company_id) AS company_id
      FROM career_history ch
      JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
     WHERE ch.state = 'accepted' AND ch.company_id IS NOT NULL
     GROUP BY ch.manager_id
    HAVING count(DISTINCT ch.company_id) = 1
)
UPDATE reviews r
   SET attributed_company_id = sole_employer.company_id,
       attribution_basis     = 'inferred'
  FROM sole_employer
 WHERE r.manager_id = sole_employer.manager_id
   AND r.attribution_basis = 'none'
   AND r.role_basis = 'legacy'
   AND NOT r.weight;

DO $$
DECLARE
    v_attributed BIGINT;
    v_unknown    BIGINT;
BEGIN
    SELECT count(*) INTO v_attributed
      FROM reviews WHERE attribution_basis IN ('inferred', 'submitted') AND role_basis = 'legacy';
    SELECT count(*) INTO v_unknown
      FROM reviews WHERE attribution_basis = 'none' AND role_basis = 'legacy'
                     AND deleted_at IS NULL AND NOT weight;

    RAISE NOTICE 'V113: % legacy ratings now carry an employer', v_attributed;
    RAISE NOTICE 'V113: % live legacy ratings still carry none, because the employer cannot be '
                 'identified from the employment record without guessing. They count toward their '
                 'manager and toward no company', v_unknown;
END $$;


-- ── 3. THE AFFILIATION AVERAGE, WEIGHTED ────────────────────────────────────
--
-- Identical to the V89 definition except for avg_rating, which was
--
--     ROUND(AVG(m.overall_rating) FILTER (...), 1)
--
-- and is now the total of each manager's ratings divided by how many there are.
-- Same population, same filter, same rounding: a manager with no ratings is
-- still excluded, or an unrated row would drag the figure toward whatever
-- default it carries.
--
-- WHY THIS STILL READS THE MANAGER CACHE RATHER THAN `reviews`.
--
-- An exact average would scan the ratings themselves. It would also make
-- `reviews` a source of this projection, and company_stats_live has no trigger
-- on `reviews` - the affiliation family is maintained from `managers`, which the
-- application rewrites through recalculate() after every rating change. Adding a
-- source without adding its triggers is how a read table silently diverges, and
-- section 21 of the operating rules forbids exactly that.
--
-- So the weights come from the same cached columns the projection already
-- depends on. Each manager's overall_rating is stored to one decimal, so this
-- can differ from an exact average over every rating by at most 0.05 before
-- rounding. That is the deliberate trade: a figure within half a displayed digit
-- of exact, against a second source of truth that nothing maintains.
--
-- The employment-attributed average beside it IS exact, because V101's triggers
-- do maintain it from `reviews`.

CREATE OR REPLACE FUNCTION company_stats_expected(p_company_ids BIGINT[] DEFAULT NULL)
RETURNS TABLE (
    company_id           BIGINT,
    manager_count        BIGINT,
    total_reviews        BIGINT,
    avg_rating           NUMERIC,
    logo_url             TEXT,
    workplace_count      BIGINT,
    workplace_avg_rating NUMERIC,
    interview_count      BIGINT,
    interview_avg_rating NUMERIC
)
LANGUAGE sql STABLE AS $$
    SELECT c.id,
           COUNT(DISTINCT m.id)::BIGINT,
           COALESCE(SUM(m.reviews_count), 0)::BIGINT,
           -- The average MANAGER rating at this company. Distinct from workplace_avg_rating (the
           -- average rating OF the company) and interview_avg_rating. Three different questions.
           --
           -- WEIGHTED BY HOW MANY RATINGS EACH MANAGER ACTUALLY HAS. This was AVG(m.overall_rating),
           -- which averaged the managers rather than the ratings: a manager with one rating of 1.0
           -- and a manager with ten of 5.0 reported 3.0, when every person who rated anybody here
           -- but one said 5. Only managers who have ratings count, or an unrated manager would drag
           -- the figure toward whatever default their row carries.
           ROUND((SUM(m.overall_rating * m.reviews_count) FILTER (
                      WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0)
                  / NULLIF(SUM(m.reviews_count) FILTER (
                      WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0), 0)
                 )::NUMERIC, 1)::NUMERIC(3,1),
           -- Prefer a logo.dev URL from a manager row, then the company's own, then any manager's.
           -- Unchanged from V47/V89 - this is a display preference, not an aggregate.
           COALESCE(
               MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
               c.logo_url,
               MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
           (SELECT COUNT(*) FROM company_reviews cr
             WHERE cr.company_id = c.id AND cr.deleted_at IS NULL)::BIGINT,
           (SELECT ROUND(AVG(cr.overall_rating)::NUMERIC, 1) FROM company_reviews cr
             WHERE cr.company_id = c.id AND cr.deleted_at IS NULL)::NUMERIC(3,1),
           (SELECT COUNT(*) FROM interview_reviews ir
             WHERE ir.company_id = c.id AND ir.deleted_at IS NULL)::BIGINT,
           (SELECT ROUND(AVG(ir.overall_rating)::NUMERIC, 1) FROM interview_reviews ir
             WHERE ir.company_id = c.id AND ir.deleted_at IS NULL)::NUMERIC(3,1)
    FROM companies c
    LEFT JOIN managers m ON m.company_id = c.id
        AND m.approval_status IN ('approved', 'ghost')
        AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
    WHERE c.status <> 'merged'
      AND (p_company_ids IS NULL OR c.id = ANY (p_company_ids))
    GROUP BY c.id, c.logo_url;
$$;


-- ── 3b. THE DRIFT CHECKER CAN SEE A WRONG AVERAGE ───────────────────────────
--
-- V90 compared counts and the logo and deliberately left the averages out, for a
-- reason it wrote down:
--
--     "An average that disagrees always has a count that disagrees behind it,
--      and comparing rounded numerics invites a false alarm over the last
--      decimal place - a checker that cries wolf gets switched off."
--
-- THE FIRST HALF IS NOT TRUE, and the test that found it is in both repositories:
-- a writer from a jar older than this migration recomputes avg_rating as
-- AVG(m.overall_rating) and leaves manager_count, total_reviews and both other
-- counts identical. Every count agrees and the number on screen is wrong. The
-- same is true of any future bug in the averaging itself, which is exactly the
-- class of defect this migration exists to correct - and the reconciliation
-- routine could not have reported it.
--
-- The second half is addressed rather than dismissed. company_stats_expected
-- rounds to NUMERIC(3,1) and company_stats_live stores what it was given, so
-- both sides of this comparison are produced by the same expression at the same
-- precision. There is no last decimal place to disagree about: either the
-- projection holds what the current definition computes, or it does not.
--
-- Section 21's fifth rule for a read table is that a reconciliation routine
-- compares it against source and alerts on drift. The averages are the figures
-- people actually read, and they were the ones nothing reconciled.

CREATE OR REPLACE FUNCTION company_stats_detect_drift(p_limit INT DEFAULT 1000)
RETURNS TABLE (company_id BIGINT, field TEXT, expected TEXT, actual TEXT, drift_kind TEXT) AS $$
    WITH expected AS (
        SELECT * FROM company_stats_expected(NULL)
    ),
    compared AS (
        SELECT COALESCE(e.company_id, cs.company_id) AS cid,
               e.company_id IS NULL                 AS obsolete,
               cs.company_id IS NULL                AS missing,
               e.manager_count, cs.manager_count   AS a_manager_count,
               e.total_reviews, cs.total_reviews   AS a_total_reviews,
               e.workplace_count, cs.workplace_count AS a_workplace_count,
               e.interview_count, cs.interview_count AS a_interview_count,
               e.logo_url,      cs.logo_url        AS a_logo_url,
               e.avg_rating,    cs.avg_rating      AS a_avg_rating,
               e.workplace_avg_rating, cs.workplace_avg_rating AS a_workplace_avg_rating,
               e.interview_avg_rating, cs.interview_avg_rating AS a_interview_avg_rating
        FROM expected e
        FULL OUTER JOIN company_stats_live cs ON cs.company_id = e.company_id
    )
    SELECT cid, field, expected, actual, kind FROM (
        SELECT cid, 'row' AS field, 'present' AS expected, 'absent' AS actual,
               'missing_row' AS kind, 0 AS ord
        FROM compared WHERE missing
        UNION ALL
        SELECT cid, 'row', 'absent', 'present', 'obsolete_row', 1
        FROM compared WHERE obsolete
        UNION ALL
        SELECT cid, 'manager_count', manager_count::TEXT, a_manager_count::TEXT, 'value', 2
        FROM compared WHERE NOT missing AND NOT obsolete AND manager_count <> a_manager_count
        UNION ALL
        SELECT cid, 'total_reviews', total_reviews::TEXT, a_total_reviews::TEXT, 'value', 3
        FROM compared WHERE NOT missing AND NOT obsolete AND total_reviews <> a_total_reviews
        UNION ALL
        SELECT cid, 'workplace_count', workplace_count::TEXT, a_workplace_count::TEXT, 'value', 4
        FROM compared WHERE NOT missing AND NOT obsolete AND workplace_count <> a_workplace_count
        UNION ALL
        SELECT cid, 'interview_count', interview_count::TEXT, a_interview_count::TEXT, 'value', 5
        FROM compared WHERE NOT missing AND NOT obsolete AND interview_count <> a_interview_count
        UNION ALL
        SELECT cid, 'logo_url', logo_url, a_logo_url, 'value', 6
        FROM compared WHERE NOT missing AND NOT obsolete AND logo_url IS DISTINCT FROM a_logo_url
        -- IS DISTINCT FROM, because NULL is a real answer here: a company nobody has rated has no
        -- average, and "it has one when it should not" is drift as much as a wrong number is.
        UNION ALL
        SELECT cid, 'avg_rating', avg_rating::TEXT, a_avg_rating::TEXT, 'value', 7
        FROM compared WHERE NOT missing AND NOT obsolete
                        AND avg_rating IS DISTINCT FROM a_avg_rating
        UNION ALL
        SELECT cid, 'workplace_avg_rating', workplace_avg_rating::TEXT,
               a_workplace_avg_rating::TEXT, 'value', 8
        FROM compared WHERE NOT missing AND NOT obsolete
                        AND workplace_avg_rating IS DISTINCT FROM a_workplace_avg_rating
        UNION ALL
        SELECT cid, 'interview_avg_rating', interview_avg_rating::TEXT,
               a_interview_avg_rating::TEXT, 'value', 9
        FROM compared WHERE NOT missing AND NOT obsolete
                        AND interview_avg_rating IS DISTINCT FROM a_interview_avg_rating
    ) AS drift
    ORDER BY cid, ord
    LIMIT p_limit;
$$ LANGUAGE sql STABLE;


-- ── 4. THE FIGURES ON SCREEN, CORRECTED ─────────────────────────────────────
--
-- Both projections are rebuilt, because both are wrong right now: every company
-- average is a mean of means, and every company's employment figures are missing
-- the ratings that arrived after V102.
--
-- Order matters. The employment rebuild runs before the triggers come back on,
-- for the reason V102 recorded: re-enabling first would make the rebuild fire
-- them again, and the per-company path measured 111 seconds against the
-- rebuild's 554 ms.

SELECT refresh_company_employment_stats();

ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_ins;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_upd_new;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_upd_old;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_del;

-- NULL means every company. company_stats_apply takes the ids in ascending order
-- and an advisory lock per company, so this is the same path maintenance uses.
SELECT company_stats_apply(NULL::BIGINT[]);

/*
  A guard, because "the triggers were left disabled" is a silent, permanent
  defect: the projection would stop tracking new ratings and the first symptom
  would be a company page whose figures stopped moving weeks earlier.

  tgenabled is 'O' for an enabled trigger and 'D' for a disabled one.
*/
DO $$
DECLARE
    v_disabled TEXT;
BEGIN
    SELECT string_agg(tgname, ', ') INTO v_disabled
      FROM pg_trigger
     WHERE tgrelid = 'reviews'::regclass
       AND tgname LIKE 'reviews_employment_stats%'
       AND tgenabled = 'D';

    IF v_disabled IS NOT NULL THEN
        RAISE EXCEPTION 'V113 would leave these triggers disabled: %. The projection would stop '
                        'tracking new ratings silently.', v_disabled;
    END IF;
END $$;
