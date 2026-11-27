-- EVERY LEGACY RATING WRITTEN BEFORE V113 IS ATTRIBUTED TO NO COMPANY, SO THE COMPANY PAGE SAYS
-- "0 managers" AND "no reviews yet" ON A COMPANY THAT PLAINLY HAS RATINGS.
--
-- REPORTED, 2026-11-26: IBM's profile showed "Manager opinions / 0 managers" with two managers
-- listed and one rating of 2.9 visible in the tab beside it, and "no reviews yet" under a company
-- that has been rated.
--
-- WHAT IS ACTUALLY WRONG
--
-- The Manager opinions block reads the EMPLOYMENT-ATTRIBUTED population: ratings tied to this
-- company through a claim, a withdrawn role, or attributed_company_id. The affiliation figures
-- beside it (the Managers tab, the directory) read managers.company_id instead, which is why the
-- same rating is visible one tab away. Both numbers are right about what they measure; one of them
-- was measuring an empty set.
--
-- V102 backfilled attribution once, from career_history. V113 added
-- attribute_legacy_rating(), a BEFORE INSERT trigger, so every rating written from then on gets an
-- employer - including by the rating's own submitted company name, which V102 never used. Verified
-- on 2026-11-26: a fresh insert naming "IBM" comes out attribution_basis = 'submitted',
-- attributed_company_id = 12.
--
-- Nothing ever went back for the ratings written BETWEEN those two migrations, or for the ones
-- V102's career-history rules could not reach. A manager created by search or by the add form has
-- no career_history at all, so V102 attributed nothing for them, while the rating itself carries
-- the employer the contributor typed. Locally that left 19 of 25 live ratings attributed to
-- nobody, and all 19 resolve by their own submitted company name.
--
-- WHAT THIS DOES
--
-- Applies the SAME three rules the trigger applies, to the rows it never saw. Nothing is guessed:
-- the rules are unchanged, and a rating whose employer still cannot be identified stays 'none' and
-- keeps counting toward its manager and toward no company, exactly as before.
--
-- ONE IMPLEMENTATION OF THE RULES, NOT TWO
--
-- V102 wrote its backfill as its own SQL and V113 wrote the trigger as plpgsql, so the two already
-- disagreed: the trigger grew a third rule that the backfill never had. Writing a third copy here
-- would guarantee the next drift, so the rules move into legacy_rating_employer() and both callers
-- read them from there. That is the only reason this migration touches the trigger function at all;
-- its behaviour is unchanged apart from the NULL-safety noted below.

-- ── 1. the rules, in one place ──────────────────────────────────────────────

CREATE OR REPLACE FUNCTION legacy_rating_employer(
        p_manager_id      BIGINT,
        p_worked_from     DATE,
        p_worked_until    DATE,
        p_manager_company TEXT)
RETURNS TABLE (out_company_id BIGINT, out_basis TEXT)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_count   INT;
    v_company BIGINT;
BEGIN
    /*
      Rule 1: the employment the rating's own work period falls inside.

      Calendar days on both sides. V97 made start_day and end_day generated DATE columns precisely
      so an employment's identity never depends on the session's timezone.
    */
    IF p_worked_from IS NOT NULL THEN
        SELECT count(DISTINCT ch.company_id), min(ch.company_id)
          INTO v_count, v_company
          FROM career_history ch
          JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
         WHERE ch.manager_id = p_manager_id
           AND ch.state = 'accepted'
           AND ch.company_id IS NOT NULL
           AND COALESCE(ch.start_day, '-infinity'::date)
               <= COALESCE(p_worked_until, 'infinity'::date)
           AND COALESCE(ch.end_day, 'infinity'::date) >= p_worked_from;

        IF v_count = 1 THEN
            RETURN QUERY SELECT v_company, 'inferred'::TEXT;
            RETURN;
        END IF;
    END IF;

    -- Rule 2: a manager with exactly one employer ever leaves no room for doubt.
    SELECT count(DISTINCT ch.company_id), min(ch.company_id)
      INTO v_count, v_company
      FROM career_history ch
      JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
     WHERE ch.manager_id = p_manager_id
       AND ch.state = 'accepted'
       AND ch.company_id IS NOT NULL;

    IF v_count = 1 THEN
        RETURN QUERY SELECT v_company, 'inferred'::TEXT;
        RETURN;
    END IF;

    /*
      Rule 3: the employer the contributor named, resolved to the row we hold for it.

      Matched on the same normalised key as companies_name_ci, which is UNIQUE on
      lower(trim(name)), so "one company or none" is a property of the schema rather than a hope.
      A merged company is excluded, so a rating never resolves to a row that has been folded away.
    */
    IF p_manager_company IS NOT NULL AND btrim(p_manager_company) <> '' THEN
        SELECT count(*), min(c.id)
          INTO v_count, v_company
          FROM companies c
         WHERE c.status <> 'merged'
           AND lower(btrim(c.name)) = lower(btrim(p_manager_company));

        IF v_count = 1 THEN
            RETURN QUERY SELECT v_company, 'submitted'::TEXT;
            RETURN;
        END IF;
    END IF;

    -- Nothing settled it, and nothing is guessed. 'none' is a real answer: the rating counts
    -- toward its manager and toward no company, and the projection already handles it.
    RETURN QUERY SELECT NULL::BIGINT, 'none'::TEXT;
END $$;

COMMENT ON FUNCTION legacy_rating_employer(BIGINT, DATE, DATE, TEXT) IS
    'The employer of a legacy rating, by the three rules in order: the employment its period falls '
    'inside, the manager''s only employer ever, then the company name the contributor submitted. '
    'Read by attribute_legacy_rating() on insert and by V118''s backfill, so the two cannot drift.';

-- ── 2. the trigger, now reading those rules rather than holding its own copy ─

CREATE OR REPLACE FUNCTION attribute_legacy_rating() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_employer RECORD;
BEGIN
    /*
      Only a legacy rating that nothing has attributed. A claimed or withdrawn rating gets its
      employer from its claim or its kept role, and reviews_attribution_follows_basis refuses
      anything else for those.

      COALESCE on weight, which the previous version read bare: a NULL there made the whole
      condition NULL, the IF was not taken, and the rating was attributed anyway. The column is NOT
      NULL today, so this changed no row; it is the difference between relying on that and
      depending on it.
    */
    IF NEW.role_basis <> 'legacy'
       OR NEW.attribution_basis <> 'none'
       OR NEW.attributed_company_id IS NOT NULL
       OR NEW.claim_id IS NOT NULL
       OR COALESCE(NEW.weight, FALSE) THEN
        RETURN NEW;
    END IF;

    SELECT * INTO v_employer
      FROM legacy_rating_employer(NEW.manager_id, NEW.worked_from,
                                  NEW.worked_until, NEW.manager_company);

    IF v_employer.out_basis <> 'none' THEN
        NEW.attributed_company_id := v_employer.out_company_id;
        NEW.attribution_basis     := v_employer.out_basis;
    END IF;

    RETURN NEW;
END $$;

-- ── 3. the rows the trigger never saw ───────────────────────────────────────
--
-- The stats triggers come off first and the projection is rebuilt once at the end. V101's
-- rehearsal measured the per-company path at 111 seconds against the rebuild's 554 ms, so firing
-- them per row would make this migration the slow part of a deployment for no gain.

ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_ins;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_upd_new;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_upd_old;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_del;

WITH candidate AS (
    SELECT r.id, e.out_company_id, e.out_basis
      FROM reviews r
      CROSS JOIN LATERAL legacy_rating_employer(r.manager_id, r.worked_from,
                                                r.worked_until, r.manager_company) AS e
     WHERE r.role_basis            = 'legacy'
       AND r.attribution_basis     = 'none'
       AND r.claim_id              IS NULL
       AND r.attributed_company_id IS NULL
       AND COALESCE(r.weight, FALSE) = FALSE
)
UPDATE reviews r
   SET attributed_company_id = candidate.out_company_id,
       attribution_basis     = candidate.out_basis
  FROM candidate
 WHERE candidate.id = r.id
   AND candidate.out_basis <> 'none';

-- ── 4. what happened, recorded rather than left silent ──────────────────────

DO $$
DECLARE
    v_submitted BIGINT;
    v_inferred  BIGINT;
    v_left      BIGINT;
BEGIN
    SELECT count(*) INTO v_submitted
      FROM reviews WHERE role_basis = 'legacy' AND attribution_basis = 'submitted';
    SELECT count(*) INTO v_inferred
      FROM reviews WHERE role_basis = 'legacy' AND attribution_basis = 'inferred';
    SELECT count(*) INTO v_left
      FROM reviews WHERE role_basis = 'legacy' AND attribution_basis = 'none'
                     AND deleted_at IS NULL AND COALESCE(weight, FALSE) = FALSE;

    RAISE NOTICE 'V118: legacy ratings now attributed: % by submitted company name, % by '
                 'employment record', v_submitted, v_inferred;
    RAISE NOTICE 'V118: % live legacy ratings remain attributed to no company, because their '
                 'employer cannot be identified without guessing. They still count toward their '
                 'manager, and the projection reports them as unattributed', v_left;
END $$;

-- ── 5. the projection rebuilt once, then the triggers back on ───────────────

SELECT refresh_company_employment_stats();

ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_ins;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_upd_new;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_upd_old;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_del;

/*
  A guard, because "the triggers were left disabled" is a silent, permanent defect: the projection
  would stop tracking new ratings and the first symptom would be a company page whose figures
  stopped moving weeks earlier. V102 carries the same check for the same reason.

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
        RAISE EXCEPTION 'V118 would leave these triggers disabled: %. The projection would stop '
                        'tracking new ratings silently.', v_disabled;
    END IF;
END $$;
