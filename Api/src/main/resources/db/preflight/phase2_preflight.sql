-- NOTE, 2026-11-10: the version numbers named below (V99, V102, V107, V109 and the rest of the
-- V97-V112 range) are now SECTIONS INSIDE a single migration, V97__rev268_manager_identity_redesign.sql.
-- None of them ever ran in production, so they were collapsed into one file; the banners inside it
-- name each original. Run this preflight before V97, not before some separate V99 that no longer
-- exists as a file. Everything it checks is unchanged.

-- PHASE 2 PREFLIGHT. READ ONLY. An operator runs this against the target database
-- BEFORE V99 is allowed to run, so the work is known before the migration window
-- rather than discovered inside it.
--
-- NOT a migration. Flyway does not look in this directory. It writes nothing and holds
-- no locks beyond the snapshots its own queries take.
--
-- Four of the checks correspond to a gate V99 or V102 enforces for itself. The gates
-- are authoritative; these are approximate in the directions noted, and they exist so
-- that a refusal is anticipated rather than suffered.

\echo '=============================================================='
\echo ' 1. COLLATION, and what normalise_title does under it'
\echo '=============================================================='
-- title_key is a STORED generated column over lower() and [[:alnum:]], both of which
-- are collation-dependent. A collation or ICU upgrade can change what they do and
-- PostgreSQL will NOT recompute the column, so the behaviour has to be established
-- against the real database rather than assumed. Record this output with the
-- migration run.
SELECT current_setting('server_version')            AS server_version,
       datcollate, datctype
  FROM pg_database WHERE datname = current_database();

-- The three contract rules, checked as data rather than claimed as prose. Expected:
-- every row true. The function does not exist until V99, so the expression is
-- reproduced inline here.
WITH sample(label, title) AS (VALUES
  ('ascii',             'Engineering Manager'),
  ('punctuation',       '  Senior   Manager, Ops!! '),
  ('cjk',               '工程經理'),
  ('cjk other',         '產品經理'),
  ('cyrillic',          'Менеджер'),
  ('accented',          'Directeur Géneral'),
  ('digits',            'Manager II'),
  ('punctuation only',  '---'),
  ('single space',      ' ')
), n AS (
  SELECT label, title,
         COALESCE(NULLIF(btrim(regexp_replace(lower(title), '[^[:alnum:]]+', ' ', 'g')), ''),
                  btrim(lower(title))) AS key
    FROM sample
)
SELECT label, title, key,
       key <> ''                                   AS rule2_never_empty,
       length(key) > 0                             AS non_degenerate
  FROM n ORDER BY label;

-- Rule 1 is formatting only, so two DIFFERENT non-empty titles must not share a key.
-- The CJK pair is the one that mattered: under an ASCII-only expression both became ''
-- and two different jobs at one employer on one day were indistinguishable.
SELECT 'cjk pair distinct' AS check,
       COALESCE(NULLIF(btrim(regexp_replace(lower('工程經理'), '[^[:alnum:]]+',' ','g')),''), '工程經理')
    <> COALESCE(NULLIF(btrim(regexp_replace(lower('產品經理'), '[^[:alnum:]]+',' ','g')),''), '產品經理')
         AS expected_true;

\echo '=============================================================='
\echo ' 2. THE V99 COMPANY GATE. Expected: 0. V99 REFUSES otherwise'
\echo '=============================================================='
-- V99 sets career_history.company_id NOT NULL and refuses with a count while any row
-- is unresolved. There is exactly ONE production path: resolving these is an operator
-- step that happens HERE, not an alternate migration with a nullable fallback.
SELECT count(*) AS career_rows_without_company_id FROM career_history WHERE company_id IS NULL;

-- The work itself, when that count is not zero. findOrCreate each distinct company
-- name through the application, then link the rows. A blank or junk string gets no
-- invented company: an operator either links it to a real company or deletes that role
-- with explicit approval.
SELECT ch.company AS company_text, count(*) AS rows,
       (SELECT c.id FROM companies c
         WHERE lower(btrim(c.name)) = lower(btrim(ch.company)) LIMIT 1) AS existing_company_id
  FROM career_history ch WHERE ch.company_id IS NULL
 GROUP BY ch.company ORDER BY 2 DESC;

\echo '=============================================================='
\echo ' 3. THE V99 DUPLICATE GATE. Expected: 0 groups'
\echo '=============================================================='
-- Rows that are distinct under their OLD representations and collide under the NEW
-- key: two titles that normalise to one title_key, or two timestamps that fall on one
-- UTC calendar day. If any do, CREATE UNIQUE INDEX fails even with every company_id
-- populated.
--
-- APPROXIMATE, and it UNDER-reports: normalise_title() does not exist yet, so this
-- uses a plainer normalisation. The DO block inside V99 is authoritative.
SELECT manager_id, company_id,
       lower(btrim(regexp_replace(title, '\s+', ' ', 'g'))) AS title_key_approx,
       (start_date AT TIME ZONE 'UTC')::date                AS start_day,
       count(*)                  AS roles,
       array_agg(id ORDER BY id) AS ids
  FROM career_history
 WHERE start_date IS NOT NULL
 GROUP BY 1, 2, 3, 4
HAVING count(*) > 1
 ORDER BY 5 DESC;
-- Resolution never guesses, and TWO of the three resolutions rev 268 lists are
-- available here. Marking the extras superseded is NOT: superseded_by and state do not
-- exist until V99 has added them, and V99's own gate runs after that ALTER with every
-- row defaulted to state 'accepted' and period_known TRUE, so nothing can be taken out
-- of the key in advance. Found by running the gate against seeded data.
--
-- So, per group, one of:
--   * correct a title or a date that is actually wrong, which is the common case: the
--     collision is usually one role entered twice with a typo or a different time of
--     day on the same calendar date;
--   * delete the redundant row, with explicit approval, when it is a duplicate record
--     of one employment rather than two jobs;
--   * decide the rows are genuinely different jobs the normalised key cannot tell
--     apart, in which case the key itself has to be narrowed before V99 and that is a
--     change to the migration, not an operator step.
--
-- Nothing merges employment records automatically, and no title or date is rewritten
-- to make the new key tidy.

\echo '=============================================================='
\echo ' 4. THE TIMESTAMP ASSUMPTION. Measured, not assumed'
\echo '=============================================================='
-- Timezone independence is not date preservation, and only the first is provided. A
-- role whose timestamp carries a time component is one whose UTC day may differ from
-- the local day its source intended: an import of 23:30 on 1 January in Halifax is 2
-- January in UTC. A midnight-UTC timestamp cannot shift.
--
-- The error is bounded to one day in one direction. If this count is small the
-- assumption is accepted and RECORDED with the run; if it is material, the import's
-- intent is established before V99 rather than guessed. No stored timestamp is shifted
-- either way.
SELECT count(*) FILTER (WHERE (start_date AT TIME ZONE 'UTC')::time <> '00:00:00')
         AS start_with_a_time_component,
       count(*) FILTER (WHERE end_date IS NOT NULL
                          AND (end_date AT TIME ZONE 'UTC')::time <> '00:00:00')
         AS end_with_a_time_component,
       count(*) AS career_rows_with_a_start
  FROM career_history WHERE start_date IS NOT NULL;

\echo '=============================================================='
\echo ' 5. career_title_key_not_empty. Expected: 0'
\echo '=============================================================='
-- NOT in rev 268, and added because the constraint is validated against existing rows
-- the moment V99 adds it. A row whose title is empty or whitespace normalises to '',
-- which the constraint refuses, and V99 would fail on the ALTER rather than at a gate
-- that explains itself. An operator fixes or removes those rows first.
SELECT count(*) AS career_rows_with_an_empty_title
  FROM career_history WHERE btrim(coalesce(title, '')) = '';
SELECT id, manager_id, company, title FROM career_history
 WHERE btrim(coalesce(title, '')) = '' ORDER BY id LIMIT 50;

\echo '=============================================================='
\echo ' 6. V102 BACKFILL COST, and the lineage split it will produce'
\echo '=============================================================='
-- V102 rewrites every row of reviews once. This is the number of rows that pass
-- involves, and the split the lineage classification will record: 'unknown' for a
-- rating whose manager has ever absorbed another profile, because nothing records
-- which ratings moved, and 'current_association' otherwise.
SELECT count(*) AS reviews_total,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM managers m
                                       WHERE m.merged_into = r.manager_id))
         AS will_be_unknown,
       count(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM managers m
                                           WHERE m.merged_into = r.manager_id))
         AS will_be_current_association
  FROM reviews r;

-- V102 narrows the two V37 uniqueness indexes to legacy rows. They must still exist
-- under their original names for the DROP to find them. Expected: 2.
SELECT count(*) AS v37_indexes_present FROM pg_indexes
 WHERE schemaname = 'public'
   AND indexname IN ('idx_reviews_user_manager_role','idx_reviews_author_manager_role');

\echo '=============================================================='
\echo ' 7. THE WRITE PATHS, which are an APPLICATION precondition'
\echo '=============================================================='
-- company_id NOT NULL binds new writes as well as existing rows, and BOTH backends
-- insert career_history. Every insert path must therefore resolve a company id before
-- V99 is applied, which is the lockstep change CLAUDE.md section 10 requires. A
-- manager with no company_id is where the admin edit-approval path used to pass one
-- through, so this count is the size of the population that path can be reached for.
SELECT count(*) AS managers_without_company_id FROM managers WHERE company_id IS NULL;
