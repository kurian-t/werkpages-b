-- ============================================================================
-- V102: legacy employment attribution
--
-- Phase 5's legacy reconciliation. Without it Phase 5 ships showing ZERO: every
-- rating in production is legacy and carries attribution_basis = 'none',
-- because V97 introduced the column and deliberately did not guess. Found by
-- the populated migration rehearsal, which reported 17,100 ratings and 0
-- attributed.
--
-- ── WHY THIS DOES NOT USE reviews.manager_company ───────────────────────────
--
-- The obvious rule is "attribute by the company the contributor named", and the
-- first version of this migration did exactly that. IT WAS WRONG, and the check
-- that found it was asking whether `manager_company` is really the
-- contributor's original assertion.
--
-- It is not. Six code paths rewrite it, and one of them is fatal here:
--
--     ManagerRepository, when a manager's company is EDITED:
--         UPDATE reviews SET manager_company = $1
--          WHERE manager_id = $2 AND manager_company = $3
--
-- So when a manager moves from Adobe to MSN through a profile edit, every one
-- of their Adobe ratings has its recorded employer rewritten to MSN. Attributing
-- from that column would move historical ratings to the manager's new employer -
-- precisely the retroactive attribution Phase 5 exists to prevent, arriving
-- through a column that looked immutable and is not.
--
-- ── WHAT IT USES INSTEAD: THE EMPLOYMENT THE RATING DESCRIBES ───────────────
--
-- A rating carries its own work period, `worked_from` and `worked_until`: the
-- months the contributor says they reported to this manager. `career_history`
-- carries the employments, each with a company and a period. The employment a
-- rating was about is the one whose period COVERS the rating's.
--
-- That is evidence about the rating itself, it is what Rev 268 models
-- attribution as everywhere else, and no company-rename or profile-edit path
-- touches it.
--
-- ── AMBIGUITY IS NOT ATTRIBUTED ─────────────────────────────────────────────
--
-- Two rules, in order, and anything they cannot settle is left alone:
--
--   1. the rating's period overlaps EXACTLY ONE accepted employment -> that one;
--   2. the rating has no usable period, but the manager has EXACTLY ONE accepted
--      employment ever -> that one, because there is no other employer the
--      rating could be about;
--   3. otherwise -> 'none'. It counts toward its manager and toward no company.
--
-- Two overlapping employments at different companies is a genuine ambiguity and
-- guessing between them would publish an experience under an employer it may
-- not belong to. "Unknown" is the honest answer and the projection already
-- handles it.
-- ============================================================================

-- ── 0a. a basis that says "we worked this out", distinct from "they told us" ─
--
-- V97 gave a legacy rating three possible bases: 'submitted', 'unresolved' and
-- 'none'. This migration needs a fourth, and the reason is a point of honesty
-- about evidence.
--
-- 'submitted' means the CONTRIBUTOR named the employer. Everything this
-- migration attributes was instead DERIVED - from the work period they stated,
-- matched against the manager's employment record. That is good evidence and it
-- is not the same thing, and a statistic should never be unable to say which it
-- rests on.
--
-- Stamping a derived attribution 'submitted' would also destroy the
-- distinction permanently: once the backfill has run there would be no way to
-- tell a contributor's own assertion from this system's inference, so a later
-- decision to treat them differently - to show provenance on a company page, to
-- re-derive the inferred ones under a better rule, to exclude them from a
-- disputed figure - would have nothing to work from.
--
-- So 'inferred' joins the set. rating_employment_company in V101 reads the same
-- column for both, so the FIGURES are identical; only the provenance differs.

ALTER TABLE reviews DROP CONSTRAINT reviews_attribution_known;
ALTER TABLE reviews ADD CONSTRAINT reviews_attribution_known
    CHECK (attribution_basis IN
           ('role','withdrawn_role','submitted','inferred','unresolved','none'));

ALTER TABLE reviews DROP CONSTRAINT reviews_attribution_follows_basis;
ALTER TABLE reviews ADD CONSTRAINT reviews_attribution_follows_basis CHECK (
    CASE role_basis
      WHEN 'claimed'   THEN attribution_basis = 'role'
      WHEN 'withdrawn' THEN attribution_basis =
           CASE WHEN withdrawn_role_id IS NOT NULL THEN 'withdrawn_role'
                ELSE 'unresolved' END
      -- 'inferred' is legal ONLY for a legacy rating. A claimed rating's employer
      -- comes from its claim and must never be guessed at, which is what keeping
      -- this inside the legacy branch enforces.
      WHEN 'legacy'    THEN attribution_basis
                            IN ('submitted','inferred','unresolved','none')
    END);

/*
  And a rating whose basis is 'inferred' must actually carry a company.

  Without this, 'inferred' could be set with attributed_company_id NULL, which
  would claim an inference was made while naming nothing - a rating that counts
  toward no company while asserting that its employer is known.
*/
ALTER TABLE reviews ADD CONSTRAINT reviews_inferred_names_a_company
    CHECK (attribution_basis <> 'inferred' OR attributed_company_id IS NOT NULL);

-- ── 0. the projection's incremental triggers, off for the backfill ──────────
--
-- MEASURED: without this, V102 took 161,645 ms on a representative database.
-- With it, see the timing in the rehearsal output. Nearly three minutes of
-- ACCESS EXCLUSIVE-adjacent contention on `reviews` is not a slow migration, it
-- is an outage: every rating submission blocks behind it.
--
-- The cause is the same one that made V101 take 111 seconds before it was
-- rewritten. V101's statement triggers recompute each affected company's
-- projection, and a backfill that touches 16,000 ratings across 200 companies
-- makes them recompute 200 companies - twice, once per UPDATE below - each
-- recomputation scanning the ratings again.
--
-- Nothing is lost by switching them off here, because step 4 rebuilds the whole
-- projection set-based afterwards. The incremental path exists to keep the
-- projection fresh for ONE rating changing; it is the wrong tool for changing
-- all of them, and using it is strictly slower than the rebuild it would be
-- replaced by.
--
-- DISABLE TRIGGER takes a lock on `reviews` and is transactional, so these are
-- restored when the migration commits - and restored automatically if it rolls
-- back, which is why this is safe to do in the middle of a migration rather
-- than something an operator has to remember to undo.

ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_ins;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_upd_new;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_upd_old;
ALTER TABLE reviews DISABLE TRIGGER reviews_employment_stats_del;

-- ── 1. attribute by the employment the rating's period falls inside ─────────

WITH candidate AS (
    SELECT r.id AS review_id,
           ch.company_id,
           -- How many accepted employments the period could belong to. More than one is
           -- ambiguity, and ambiguity is not attributed.
           count(*) OVER (PARTITION BY r.id) AS overlapping
      FROM reviews r
      JOIN career_history ch
        ON ch.manager_id = r.manager_id
       AND ch.state = 'accepted'
       AND ch.company_id IS NOT NULL
       -- Calendar days on both sides. V97 made start_day and end_day generated DATE columns
       -- precisely so an employment's identity never depends on the session's timezone.
       AND COALESCE(ch.start_day, '-infinity'::date) <= COALESCE(r.worked_until, 'infinity'::date)
       AND COALESCE(ch.end_day,    'infinity'::date)  >= r.worked_from
      JOIN companies c ON c.id = ch.company_id AND c.status <> 'merged'
     WHERE r.attribution_basis = 'none'
       AND r.role_basis = 'legacy'
       AND r.worked_from IS NOT NULL
)
UPDATE reviews r
   SET attributed_company_id = candidate.company_id,
       attribution_basis     = 'inferred'
  FROM candidate
 WHERE r.id = candidate.review_id
   AND candidate.overlapping = 1;

-- ── 2. a manager with exactly one employer ever leaves no room for doubt ────

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
   AND r.role_basis = 'legacy';

/*
  Deliberately NOT restricted to ratings without a period.

  A rating whose period overlapped two employments at the SAME company is already attributed by
  rule 1 (one distinct company, so `overlapping` counts the rows, not the companies - which is why
  rule 2 has to catch it). And a rating whose period overlaps nothing, at a manager who has only
  ever had one employer, still has only one employer it could be about. Both are unambiguous.

  What this cannot reach, and must not: a manager with two employers where the rating's period
  fits neither, or fits both. Those stay 'none'.
*/

-- ── 3. what happened, recorded rather than left silent ──────────────────────

DO $$
DECLARE
    v_attributed BIGINT;
    v_ambiguous  BIGINT;
BEGIN
    SELECT count(*) INTO v_attributed
      FROM reviews WHERE attribution_basis = 'inferred' AND role_basis = 'legacy';
    SELECT count(*) INTO v_ambiguous
      FROM reviews WHERE attribution_basis = 'none' AND role_basis = 'legacy'
                     AND deleted_at IS NULL;

    RAISE NOTICE 'V102: % legacy ratings attributed, basis ''inferred'', to the employment '
                 'they describe', v_attributed;
    RAISE NOTICE 'V102: % live legacy ratings left unattributed because the employer cannot be '
                 'identified without guessing; they count toward their manager and toward no '
                 'company', v_ambiguous;
END $$;

-- ── 4. the triggers back on, and the projection rebuilt once ────────────────
--
-- The order matters. Re-enabling first would make the rebuild fire them again;
-- the rebuild is set-based and needs no help, and V101's rehearsal measured the
-- per-company path at 111 seconds against the rebuild's 554 ms.

SELECT refresh_company_employment_stats();

ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_ins;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_upd_new;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_upd_old;
ALTER TABLE reviews ENABLE TRIGGER reviews_employment_stats_del;

/*
  A guard, because "the triggers were left disabled" is a silent, permanent
  defect: the projection would simply stop tracking new ratings, and the first
  symptom would be a company page whose figures stopped moving weeks earlier.

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
        RAISE EXCEPTION 'V102 would leave these triggers disabled: %. The projection '
                        'would stop tracking new ratings silently.', v_disabled;
    END IF;
END $$;
