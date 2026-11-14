-- NOTE, 2026-11-10: the version numbers named below (V99, V102, V107, V109 and the rest of the
-- V97-V112 range) are now SECTIONS INSIDE a single migration, V97__rev268_manager_identity_redesign.sql.
-- None of them ever ran in production, so they were collapsed into one file; the banners inside it
-- name each original. Run this preflight before V97, not before some separate V99 that no longer
-- exists as a file. Everything it checks is unchanged.

-- V107 OPERATOR PREFLIGHT. Read-only. Run on production BEFORE deploying V107.
--
-- NOT A MIGRATION. Flyway does not scan this directory, and this file must never be moved into
-- db/migrations.
--
-- WHY IT EXISTS. V107 backfills `author_erased = TRUE` for ratings erased by the OLD account
-- deletion code, identified by `author = 'Anonymous User' AND user_id IS NULL`. That is a BYLINE,
-- and a byline is not provenance. The supporting evidence is good but not proof:
--
--   * an account-less submission gets ManagerService.generatePseudonym(), which is
--     Adjective + Animal + two digits ("WarmWolf61") and cannot produce this literal;
--   * the literal is written in exactly one place in either application, the account-deletion path.
--
-- Neither fact rules out an import, an administrative action, or an older release having written
-- it. So the rows are listed here for review rather than assumed.
--
-- The migration itself refuses to run if any row carries the byline while still attached to an
-- account, because that would disprove the premise outright. This preflight covers the cases a
-- single assertion cannot judge.

\echo '=== 1. How many rows the backfill would claim ==='
SELECT count(*) AS would_be_backfilled
  FROM reviews
 WHERE author = 'Anonymous User'
   AND user_id IS NULL;

\echo '=== 2. THE GATE the migration enforces: must be zero, or V107 aborts ==='
-- The byline on a rating that still has an owner proves it does not exclusively mean erasure.
SELECT count(*) AS byline_with_account_attached
  FROM reviews
 WHERE author = 'Anonymous User'
   AND user_id IS NOT NULL;

\echo '=== 3. Every candidate, with its provenance, for review ==='
-- created_at = updated_at would be suspicious: the erasure path writes updated_at = now(), so a
-- genuinely erased row was modified after it was created. A row that was never modified did not
-- get this byline from the deletion path.
SELECT r.id,
       r.manager_id,
       r.manager_company,
       r.manager_title,
       r.created_at,
       r.updated_at,
       r.updated_at > r.created_at AS was_modified_after_creation,
       r.role_basis,
       r.legacy_dedup_state,
       r.dates_hidden,
       r.deleted_at IS NOT NULL    AS is_deleted,
       r.weight                    AS is_synthetic_seed
  FROM reviews r
 WHERE r.author = 'Anonymous User'
   AND r.user_id IS NULL
 ORDER BY r.created_at;

\echo '=== 4. Candidates NOT modified after creation: these need explaining before you proceed ==='
SELECT count(*) AS unexplained_candidates
  FROM reviews
 WHERE author = 'Anonymous User'
   AND user_id IS NULL
   AND updated_at <= created_at;

\echo '=== 5. Cross-check against the deletion audit, where one exists ==='
-- review_deletions records a contributor deleting a rating. It is not an account-deletion log, so
-- this is corroboration rather than proof: a manager with erased ratings and no deletion history at
-- all is worth a second look.
SELECT count(*) AS erased_candidates_on_managers_with_no_deletion_history
  FROM reviews r
 WHERE r.author = 'Anonymous User'
   AND r.user_id IS NULL
   AND NOT EXISTS (SELECT 1 FROM review_deletions d WHERE d.manager_id = r.manager_id);

\echo '=== 6. Would any two candidates collide on the legacy key? ==='
-- If this returns rows, those ratings are exactly the ones the old index could never have held
-- together, which would itself be evidence something other than the deletion path wrote them.
SELECT lower(TRIM(BOTH FROM author)) AS author_key, manager_id,
       lower(TRIM(BOTH FROM COALESCE(manager_title, '')))   AS title_key,
       lower(TRIM(BOTH FROM COALESCE(manager_company, ''))) AS company_key,
       count(*) AS n
  FROM reviews
 WHERE author = 'Anonymous User' AND user_id IS NULL
   AND weight = FALSE AND role_basis = 'legacy' AND legacy_dedup_state = 'indexed'
 GROUP BY 1,2,3,4
HAVING count(*) > 1;

\echo '=== HOW TO READ THIS ==='
\echo 'Section 2 must be 0, or V107 aborts by design.'
\echo 'Section 4 should be 0. Each row there is a candidate the deletion path cannot explain.'
\echo 'Section 1 should match the number of account deletions you expect to have happened.'
\echo 'If section 1 is small, read section 3 and confirm each row individually.'
