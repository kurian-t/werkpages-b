-- THE TITLE PICKER OFFERS "VP Sales" BECAUSE SOMEBODY'S PROFILE SAYS "VP Sales".
--
-- REPORTED, 2026-11-27, from production: typing "Vp" offers
--
--     Vice President · Vice President, Talent · VP Sales · Vice President, Finance
--     VP of Product · Vice President of Sales · Vice President, Exhibits · Vice President of People
--
-- Two complaints in one list, and neither is a display bug. The picker's first tier is the
-- spellings THIS PRODUCT'S OWN PEOPLE have used, offered so that the next person copies one
-- instead of inventing a ninth. So "VP Sales" is on a real profile, and the list is inconsistent
-- because the stored titles are.
--
-- V116 installed the curated catalogue and deliberately changed no existing row. This is the
-- cleanup that was left for afterwards, approved on 2026-11-27.
--
-- WHAT IS REWRITTEN
--
--   1. An acronym with exactly ONE reading in the catalogue is expanded:
--        VP Sales            -> Vice President Sales
--        SVP of Engineering  -> Senior Vice President of Engineering
--
--   2. "<President rank> <department>" becomes "<rank> of <department>":
--        VP Sales -> Vice President of Sales
--
-- A COMMA RULE WAS WRITTEN AND THEN REMOVED, 2026-11-27. "<catalogue rank>, <rest>" was rewritten
-- with "of" on the assumption that a comma introduces a department. The production preview showed
-- that is false often enough to be unusable: "Co-Founder, CEO & Director" became "Co-Founder of
-- Chief Executive Officer & Director", "Pharmacist, Pharmacy Manager" became "Pharmacist of
-- Pharmacy Manager", and "Director of Finance, Hotels and Incubations" gained a second "of". A
-- comma in a real title separates two roles as often as it introduces a department, so the
-- comma spelling is now left exactly as it was typed.
--
-- WHAT IS NOT, AND WHY
--
-- AN AMBIGUOUS ACRONYM IS NEVER EXPANDED. PM is Product, Program or Project Manager; AVP is
-- Assistant or Associate Vice President; GM is General or Group Manager; CPO has four readings.
-- Expanding one of those would write a coin toss into a title real people read, and nothing would
-- record that it had been a guess. The set is read out of the catalogue rather than hard-coded, so
-- adding a second reading for an acronym retires it from expansion automatically.
--
-- Typos are not repaired. "Managerrrr" is left exactly as typed: fixing it would be a guess about
-- what somebody meant.
--
-- reviews.manager_title is NOT touched. It is a per-rating snapshot of what the contributor said
-- at the time, it is nobody else's to tidy, and the picker does not read it.
--
-- EVERY REWRITE IS RECORDED, so this is reversible exactly rather than approximately. A backfill
-- over text people wrote has no inverse otherwise.

-- ── 1. expansion, limited to acronyms with one reading ──────────────────────

CREATE OR REPLACE FUNCTION expand_title_acronyms(p_title TEXT) RETURNS TEXT
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_out TEXT := p_title;
    r     RECORD;
BEGIN
    IF p_title IS NULL OR btrim(p_title) = '' THEN RETURN p_title; END IF;

    FOR r IN
        SELECT upper(btrim(jt.acronym)) AS acronym, min(jt.title) AS title
          FROM job_titles jt
         WHERE jt.acronym IS NOT NULL AND btrim(jt.acronym) <> ''
         GROUP BY upper(btrim(jt.acronym))
        HAVING count(DISTINCT jt.title) = 1
    LOOP
        /*
          NOT INSIDE PARENTHESES, which is what the lookbehind and lookahead are for.

          An acronym in brackets is a gloss of the words in front of it, so expanding it says the
          same thing twice: the production preview of 2026-11-27 turned "Chief Executive Officer
          (CEO)" into "Chief Executive Officer (Chief Executive Officer)", and did it to CTO, CFO,
          COO and SVP as well. The \w either side keeps it from firing inside another word, which
          is what \m and \M used to do.

          Case-insensitive, because people type "Vp" and "ceo" too.
        */
        v_out := regexp_replace(v_out, '(?<![(\w])' || r.acronym || '(?![)\w])', r.title, 'gi');
    END LOOP;

    RETURN v_out;
END $$;

COMMENT ON FUNCTION expand_title_acronyms(TEXT) IS
    'Expands job-title acronyms that have exactly one reading in job_titles. An acronym with two '
    'or more readings is left alone: expanding it would be a guess written into a stored title.';

-- ── 2. the house spelling ───────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION canonicalize_job_title(p_title TEXT) RETURNS TEXT
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_out  TEXT;
    v_head TEXT;
    v_rest TEXT;
BEGIN
    IF p_title IS NULL OR btrim(p_title) = '' THEN RETURN p_title; END IF;

    v_out := btrim(expand_title_acronyms(p_title));
    -- Runs of whitespace collapse. "Vice  President" is the same title typed carelessly.
    v_out := regexp_replace(v_out, '\s+', ' ', 'g');

    /*
      "<President rank> <department>" -> "<rank> of <department>".

      The comma above is a reliable signal; a bare space is not, which is why this is restricted
      to ranks ending in "President". "Vice President Sales" is a department and becomes "Vice
      President of Sales"; "Software Engineer Manager" is not, and a general rule would have made
      it "Software Engineer of Manager".

      Skipped when the remainder begins with a joining word, so "Vice President and General
      Counsel" is left as it is rather than becoming "Vice President of and General Counsel".
    */
    IF position(',' IN v_out) = 0 AND v_out !~* '\mof\M' THEN
        SELECT jt.title INTO v_head
          FROM job_titles jt
         WHERE jt.title ILIKE '%President'
           AND v_out ILIKE jt.title || ' %'
         ORDER BY length(jt.title) DESC
         LIMIT 1;

        IF v_head IS NOT NULL THEN
            v_rest := btrim(substr(v_out, length(v_head) + 1));
            /*
              PLAIN WORDS ONLY. The remainder has to look like a department and nothing else:
              letters and spaces, nothing shorter than two characters, and not a joining word.

              Without this the production preview produced "President of | Managing Director" and
              "Vice President of / Office / Credit / Customer Service Manager", because a title
              that lists two roles with a slash or a pipe is not a rank and a department.
            */
            IF v_rest ~ '^[[:alpha:]][[:alpha:] ]+$'
               AND lower(split_part(v_rest, ' ', 1)) NOT IN ('of','for','and','the') THEN
                v_out := v_head || ' of ' || v_rest;
            END IF;
        END IF;
    END IF;

    RETURN v_out;
END $$;

COMMENT ON FUNCTION canonicalize_job_title(TEXT) IS
    'The house spelling of a stored job title: unambiguous acronyms expanded, whitespace '
    'collapsed, and a department written with "of": after a comma for any catalogue rank, and \n    after a space for the President ranks, where that reading is never wrong.';

-- ── 3. what is about to change, kept so it can be put back exactly ──────────

CREATE TABLE job_title_cleanup_v119 (
    id          BIGSERIAL PRIMARY KEY,
    source      TEXT        NOT NULL CHECK (source IN ('manager', 'interview_review')),
    row_id      TEXT        NOT NULL,
    old_title   TEXT        NOT NULL,
    new_title   TEXT        NOT NULL,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE job_title_cleanup_v119 IS
    'Every title V119 rewrote, with what it was. A backfill over text people wrote has no inverse '
    'without this, and "approximately reversible" is not reversible.';

/*
  NOTHING LONGER THAN THE FORM ACCEPTS.

  managers.title is TEXT with no limit, so a long rewrite would be stored happily - but the API
  caps a submitted title at 100 characters, so it would be a value nobody could have typed and
  that the admin edit form would then refuse to save back. The production preview of 2026-11-27
  found 14 of these, all cases where expanding an acronym made an already long title longer:
  "Executive Vice President and CFO of the Company and Texas Capital Bank" and the like.

  They are left exactly as typed. An acronym kept in a 100-character title is a smaller problem
  than a title its own owner cannot edit.
*/
INSERT INTO job_title_cleanup_v119 (source, row_id, old_title, new_title)
SELECT 'manager', m.id::TEXT, m.title, canonicalize_job_title(m.title)
  FROM managers m
 WHERE m.title IS NOT NULL
   AND btrim(m.title) <> ''
   AND canonicalize_job_title(m.title) <> m.title
   AND length(canonicalize_job_title(m.title)) <= 100;

INSERT INTO job_title_cleanup_v119 (source, row_id, old_title, new_title)
SELECT 'interview_review', ir.id::TEXT, ir.role_category, canonicalize_job_title(ir.role_category)
  FROM interview_reviews ir
 WHERE ir.role_category IS NOT NULL
   AND btrim(ir.role_category) <> ''
   AND canonicalize_job_title(ir.role_category) <> ir.role_category
   AND length(canonicalize_job_title(ir.role_category)) <= 100;

-- ── 4. the rewrite ──────────────────────────────────────────────────────────
--
-- managers.title_normalized is maintained by managers_normalize_title_trigger, so it follows
-- automatically and the picker's grouping stays correct.

UPDATE managers m
   SET title = c.new_title,
       updated_at = now()
  FROM job_title_cleanup_v119 c
 WHERE c.source = 'manager' AND c.row_id = m.id::TEXT;

UPDATE interview_reviews ir
   SET role_category = c.new_title
  FROM job_title_cleanup_v119 c
 WHERE c.source = 'interview_review' AND c.row_id = ir.id::TEXT;

-- ── 5. what happened ────────────────────────────────────────────────────────

DO $$
DECLARE
    v_managers   BIGINT;
    v_interviews BIGINT;
    v_too_long   BIGINT;
    v_sample     TEXT;
BEGIN
    SELECT count(*) INTO v_managers
      FROM job_title_cleanup_v119 WHERE source = 'manager';
    SELECT count(*) INTO v_interviews
      FROM job_title_cleanup_v119 WHERE source = 'interview_review';
    SELECT string_agg(s.line, '; ') INTO v_sample FROM (
        SELECT old_title || ' -> ' || new_title AS line
          FROM job_title_cleanup_v119 ORDER BY id LIMIT 5) s;

    RAISE NOTICE 'V119: % manager titles and % interview categories rewritten to the house '
                 'spelling', v_managers, v_interviews;
    IF v_sample IS NOT NULL THEN
        RAISE NOTICE 'V119: for example: %', v_sample;
    END IF;
    SELECT count(*) INTO v_too_long
      FROM managers m
     WHERE m.title IS NOT NULL AND btrim(m.title) <> ''
       AND canonicalize_job_title(m.title) <> m.title
       AND length(canonicalize_job_title(m.title)) > 100;
    RAISE NOTICE 'V119: % titles left as typed because the house spelling would exceed the 100 '
                 'characters the form accepts', v_too_long;
    RAISE NOTICE 'V119: every change is in job_title_cleanup_v119, which is what makes this '
                 'reversible exactly rather than approximately';
END $$;
