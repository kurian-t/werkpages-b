-- NOTE, 2026-11-10: the version numbers named below (V99, V102, V107, V109 and the rest of the
-- V97-V112 range) are now SECTIONS INSIDE a single migration, V97__rev268_manager_identity_redesign.sql.
-- None of them ever ran in production, so they were collapsed into one file; the banners inside it
-- name each original. Run this preflight before V97, not before some separate V99 that no longer
-- exists as a file. Everything it checks is unchanged.

-- READ ONLY. Run before or after V109; it changes nothing and gates nothing.
--
-- V109 refuses an audit INSERT that names no actor. It deliberately does not inspect history,
-- because an existing (actor NULL, actor_withdrawn TRUE) row is exactly what V105 produces when
-- an administrator deletes their account, and those two columns cannot distinguish that from a
-- fabricated insert. Refusing every such row would reject legitimate erasures.
--
-- So this counts them instead, for an operator to look at. A number larger than the count of
-- deleted administrator accounts is worth investigating. It is not, on its own, evidence of
-- anything: provenance that was never recorded cannot be recovered from these columns, and this
-- script does not invent it.

SELECT 'claim_corrections'    AS audit_table,
       count(*)               AS rows_total,
       count(*) FILTER (WHERE actor_withdrawn) AS actor_withdrawn,
       min(corrected_at) FILTER (WHERE actor_withdrawn) AS earliest_withdrawn,
       max(corrected_at) FILTER (WHERE actor_withdrawn) AS latest_withdrawn
  FROM claim_corrections
UNION ALL
SELECT 'manager_merge_events', count(*), count(*) FILTER (WHERE actor_withdrawn),
       min(merged_at) FILTER (WHERE actor_withdrawn),
       max(merged_at) FILTER (WHERE actor_withdrawn)
  FROM manager_merge_events
UNION ALL
SELECT 'company_merges', count(*), count(*) FILTER (WHERE actor_withdrawn),
       min(merged_at) FILTER (WHERE actor_withdrawn),
       max(merged_at) FILTER (WHERE actor_withdrawn)
  FROM company_merges
UNION ALL
-- role_corrections has refused an actorless insert since V108; included so the four tables can
-- be compared in one read rather than two.
SELECT 'role_corrections', count(*), count(*) FILTER (WHERE actor_withdrawn),
       min(corrected_at) FILTER (WHERE actor_withdrawn),
       max(corrected_at) FILTER (WHERE actor_withdrawn)
  FROM role_corrections
ORDER BY audit_table;
