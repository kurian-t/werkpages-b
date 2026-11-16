-- ─────────────────────────────────────────────────────────────────────────────
-- SUPERSEDED, 2026-11-16. DO NOT RUN. The name of this file is a stale premise.
--
-- Production is NO LONGER at V96. It applied up to and including V107 on
-- 2026-11-15, after a 50-minute outage, and a version recorded in
-- flyway_schema_history cannot be rolled back.
--
-- So the gate this script existed to clear has been cleared: V97 applied, which
-- it refuses to do while any career_history row has a NULL company_id. Running
-- this now would be a no-op at best.
--
-- Kept rather than deleted because it is the record of what was done to a V96
-- production and why, and because the inspection queries in these scripts are
-- still the right way to look at this data. The file name is left alone so the
-- references to it in HANDOVER.md and MIGRATION_HISTORY.md still resolve.
--
-- Before running anything in this directory against production, read the
-- current version out of flyway_schema_history rather than out of a filename.
-- ─────────────────────────────────────────────────────────────────────────────

-- ─────────────────────────────────────────────────────────────────────────────
-- PROD AT V96: RESOLVE career_history.company_id BEFORE V97
--
-- V97 refuses, by design, while any career_history row has a NULL company_id:
--
--   V99 refuses: 78 career_history rows have no company_id.
--   Resolve them with the preflight procedure and re-run.
--
-- That gate fired on the 2026-11-14 deploy and the migration rolled back whole,
-- leaving production untouched at V96 and the API crash-looping on every boot.
-- This script is the "preflight procedure" that message names, for the company
-- gate specifically. phase2_preflight.sql remains the read-only inspection and
-- also covers the SECOND gate (title_key/start_day duplicates); run it first and
-- again afterwards.
--
-- WHAT THIS DOES NOT DO. It never invents a company for a blank or junk name and
-- it never deletes a role. Both are decisions, and rev 268 puts them with an
-- operator rather than in a migration with a nullable fallback. Anything it
-- cannot resolve honestly is REPORTED and the final gate then refuses, so you
-- learn here instead of in a failed deploy.
--
-- Transactional throughout: if the final gate refuses, nothing is committed.
-- Idempotent: re-running after a partial success changes nothing further.
-- ─────────────────────────────────────────────────────────────────────────────

\set ON_ERROR_STOP on
BEGIN;

-- ── 0. This is a PRE-V97 step ───────────────────────────────────────────────
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '97' AND success) THEN
    RAISE EXCEPTION 'V97 has already applied; this script is a pre-V97 operator step';
  END IF;
END $$;

-- ── 1. A backup of exactly the rows this touches ────────────────────────────
-- Before, not after. These are the only rows that change, so this is the whole
-- undo: UPDATE career_history ch SET company_id = b.company_id FROM <backup> b
-- WHERE b.id = ch.id.
CREATE TABLE IF NOT EXISTS career_history_company_backfill_backup AS
SELECT id, manager_id, company, company_id, now() AS backed_up_at
  FROM career_history
 WHERE company_id IS NULL;

\echo '=============================================================='
\echo ' BEFORE: rows with no company_id'
\echo '=============================================================='
SELECT count(*) AS unresolved_before FROM career_history WHERE company_id IS NULL;

-- ── 2. LINK to a company that already exists ────────────────────────────────
-- Case and whitespace insensitive, matching companies_name_ci, the unique index
-- the application relies on. This invents nothing: the company row is already
-- there and the role simply was not pointed at it.
UPDATE career_history ch
   SET company_id = c.id,
       updated_at = now()
  FROM companies c
 WHERE ch.company_id IS NULL
   AND btrim(coalesce(ch.company, '')) <> ''
   AND lower(btrim(c.name)) = lower(btrim(ch.company));

-- ── 3. CREATE the companies that genuinely are not there yet ────────────────
-- THROUGH THE SAME EXPRESSION THE APPLICATION USES, which is the point. CLAUDE.md
-- section 19 says every manager-creation path goes through
-- CompanyRepository.findOrCreate and that a second company-creation mechanism must
-- not exist. So the slug derivation and the conflict target below are copied from
-- that method verbatim (CompanyRepository.java:743) rather than reinvented:
--
--   lower(regexp_replace(regexp_replace(lower(trim($1)), '[^a-z0-9\s-]', '', 'g'),
--                        '\s+', '-', 'g'))
--   ON CONFLICT (slug)
--
-- status 'ghost' is correct and deliberate: section 18 defines it as "created
-- automatically", publicly visible, not awaiting approval. A company somebody
-- actually typed into a role is exactly that.
--
-- DISTINCT ON (slug) because two different texts can derive one slug - "Acme Inc."
-- and "Acme Inc" both give acme-inc - and ON CONFLICT cannot touch one row twice
-- in a single statement. DO NOTHING rather than DO UPDATE for the same reason: the
-- existing row is already what we want to link to.
INSERT INTO companies (name, status, slug, created_at, updated_at)
SELECT DISTINCT ON (slug) name, 'ghost', slug, now(), now()
  FROM (
    SELECT btrim(ch.company) AS name,
           lower(regexp_replace(regexp_replace(lower(btrim(ch.company)),
                 '[^a-z0-9\s-]', '', 'g'), '\s+', '-', 'g')) AS slug
      FROM career_history ch
     WHERE ch.company_id IS NULL
       AND btrim(coalesce(ch.company, '')) <> ''
  ) candidates
 WHERE slug <> ''
 ORDER BY slug, name
ON CONFLICT (slug) DO NOTHING;

-- ── 4. LINK the rest, by slug ───────────────────────────────────────────────
-- By slug rather than by name, so the texts that collapse onto one slug in step 3
-- all find the single company that now represents them.
UPDATE career_history ch
   SET company_id = c.id,
       updated_at = now()
  FROM companies c
 WHERE ch.company_id IS NULL
   AND btrim(coalesce(ch.company, '')) <> ''
   AND c.slug = lower(regexp_replace(regexp_replace(lower(btrim(ch.company)),
                      '[^a-z0-9\s-]', '', 'g'), '\s+', '-', 'g'));

-- ── 5. WHAT IS LEFT, AND WHY IT NEEDS YOU ───────────────────────────────────
-- A role whose company text is blank, or is punctuation that derives an empty
-- slug. There is no honest company to link it to and inventing one would put a
-- company nobody named into a public directory. Per rev 268 the choice is yours:
-- link it to a real company by hand, or delete that role with explicit approval.
\echo '=============================================================='
\echo ' REMAINING: these need a human decision, see section 5'
\echo '=============================================================='
SELECT ch.id, ch.manager_id, m.name AS manager, quote_literal(ch.company) AS company_text, ch.title
  FROM career_history ch
  LEFT JOIN managers m ON m.id = ch.manager_id
 WHERE ch.company_id IS NULL
 ORDER BY ch.id;

-- ── 6. V97's OWN GATE, run here so a deploy cannot be the thing that finds out ─
DO $$
DECLARE n BIGINT;
BEGIN
  SELECT count(*) INTO n FROM career_history WHERE company_id IS NULL;
  IF n > 0 THEN
    RAISE EXCEPTION
      'STILL % unresolved row(s). Nothing has been committed. Resolve the rows '
      'listed above by hand, then re-run this script.', n;
  END IF;
  RAISE NOTICE 'company gate clear: every career_history row has a company_id';
END $$;

COMMIT;

\echo '=============================================================='
\echo ' Now re-run phase2_preflight.sql. Every gate must read 0,'
\echo ' INCLUDING the duplicate gate, before deploying.'
\echo '=============================================================='
