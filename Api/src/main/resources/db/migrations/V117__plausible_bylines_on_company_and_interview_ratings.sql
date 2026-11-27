-- ─────────────────────────────────────────────────────────────────────────────
-- V117  GIVE EVERY WORKPLACE RATING AND INTERVIEW EXPERIENCE A PLAUSIBLE BYLINE
--
-- REPORTED FROM PRODUCTION, 2026-11-26. A company profile showed two opinions
-- side by side: one signed "WiseCrane53", the next signed "Anonymous employee".
-- A reader could therefore tell which opinion had been written by somebody who
-- deleted their account, which is the one fact the erasure exists to remove.
--
-- V107 fixed this for `reviews`, where the tell was the literal 'Anonymous User'
-- written into the column. The same defect in the other two tables arrived by a
-- different route and so was missed: account erasure set
-- company_reviews.author and interview_reviews.author to NULL, on a premise
-- written into UserRepository - "no author name is ever selected for display".
-- That premise was false. CompanyReviewService and InterviewService both return
-- the column, and the clients render a null one as the literal "Anonymous
-- employee" / "Anonymous candidate". A null byline was exactly as disclosing as
-- the string V107 removed.
--
-- The application now mints a handle on every path that writes these rows, and
-- substitutes a stable one at read time. This migration is for the rows already
-- written: ratings erased by the old code, and ratings that predate authorship
-- entirely. Both render identically on the page, and neither can be told apart
-- from the other, so both are given a handle.
--
-- Idempotent: every statement is scoped by the condition it removes, so a second
-- run changes nothing.
--
-- NO RECALCULATION FOLLOWS, and the schema enforces that rather than this file
-- remembering to. A byline feeds no statistic, no cache and no projection, and
-- both statement-level stats triggers on these tables are column-guarded:
-- company_review_company_stats_update fires company_stats_apply only when
-- company_id, rating_average or deleted_at actually change, and
-- rating_company_stats_update does the same on company_id, overall_rating and
-- deleted_at. An author-only UPDATE changes none of them, so v_ids comes back
-- NULL and nothing is projected.
--
-- WHICH MAKES THIS A CHEAP MIGRATION, unlike V107. That one deleted rows and had
-- to recalculate every manager and company it touched. This one rewrites a text
-- column and does no aggregate work at all, whatever the row count turns out to
-- be in production.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── WHY updated_at IS NOT BUMPED ────────────────────────────────────────────
--
-- The workplace card renders "edited <n> days ago" from updated_at. Setting it
-- here would announce that every one of these ratings was edited at the same
-- instant, which re-discloses through a timestamp precisely what the byline
-- change exists to hide. V107 did bump it on `reviews`; that was a mistake worth
-- not repeating rather than a precedent worth following.

-- ── THE VOCABULARY IS DUPLICATED HERE, as V107's note explains ──────────────
--
-- It lives in org.werkpages.repository.Pseudonyms (and its RMM twin). This is
-- the second copy, in SQL, because the alternative is a one-off application task
-- that leaves no record in the migration history that it ran. V107 holds the
-- other copy. A THIRD MUST NEVER BE MADE: if these lists ever change, neither
-- file needs updating, because the rows they named are already named.
--
-- ONE NAME PER ROW, not one per table and not one per account. random() is
-- evaluated per row in an UPDATE, which is what makes that work. A shared handle
-- would relink an erased person's ratings to each other - "these four opinions
-- are one person" - which is a fact the erasure removed everywhere else.

-- ── WHAT THIS NAMES, AND WHAT IT DELIBERATELY LEAVES ALONE ──────────────────
--
--   author IS NULL AND user_id IS NULL   -> named        (this migration)
--   author IS NULL AND user_id IS NOT NULL -> LEFT ALONE
--
-- The first population is the one that lost an identity: account erasure, which
-- nulls both columns, and anonymous publication of a captured form, which never
-- had a user. Those are the rows the report was about.
--
-- The second is a live account that chose to leave the handle blank. That is a
-- storable product state and not a defect, the upsert's
-- COALESCE(EXCLUDED.author, company_reviews.author) depends on it so that an
-- edit does not rename anybody, and Account Settings shows the author the choice
-- they made. Writing a handle into those rows would overwrite a decision.
--
-- Neither population can render as "Anonymous employee" any more regardless: the
-- public list serializer substitutes a stable handle for an absent byline. This
-- migration is about what is STORED, so that an export, an older build or any
-- other reader of the table sees a handle too.

-- ── 1. WORKPLACE RATINGS ────────────────────────────────────────────────────

DO $$
DECLARE v_company BIGINT; v_interview BIGINT; v_reviews BIGINT;
BEGIN
  SELECT count(*) INTO v_company   FROM company_reviews
   WHERE author IS NULL AND user_id IS NULL;
  SELECT count(*) INTO v_interview FROM interview_reviews
   WHERE author IS NULL AND user_id IS NULL;
  SELECT count(*) INTO v_reviews   FROM reviews
   WHERE author_erased AND author ILIKE 'anonymous%';
  RAISE NOTICE 'V117: naming % unsigned workplace rating(s)', v_company;
  RAISE NOTICE 'V117: naming % unsigned interview experience(s)', v_interview;
  RAISE NOTICE 'V117: renaming % erased manager rating(s) missed by V107', v_reviews;
END $$;

UPDATE company_reviews
   SET author = (ARRAY['Brave','Swift','Bold','Calm','Keen','Wise','Fair','Kind',
                       'Sharp','Quiet','Clear','Warm','Cool','Bright','Loyal'])
                [1 + floor(random() * 15)::int]
             || (ARRAY['Falcon','Tiger','Eagle','Wolf','Bison','Crane','Lynx',
                       'Otter','Raven','Gecko','Heron','Panda','Finch','Moose'])
                [1 + floor(random() * 14)::int]
             || (10 + floor(random() * 90)::int)::text
 WHERE author IS NULL
   AND user_id IS NULL;

-- ── 2. INTERVIEW EXPERIENCES ────────────────────────────────────────────────

UPDATE interview_reviews
   SET author = (ARRAY['Brave','Swift','Bold','Calm','Keen','Wise','Fair','Kind',
                       'Sharp','Quiet','Clear','Warm','Cool','Bright','Loyal'])
                [1 + floor(random() * 15)::int]
             || (ARRAY['Falcon','Tiger','Eagle','Wolf','Bison','Crane','Lynx',
                       'Otter','Raven','Gecko','Heron','Panda','Finch','Moose'])
                [1 + floor(random() * 14)::int]
             || (10 + floor(random() * 90)::int)::text
 WHERE author IS NULL
   AND user_id IS NULL;

-- ── 3. MANAGER RATINGS, A SECOND PASS OVER WHAT V107 ALREADY DID ────────────
--
-- V107 relabelled author = 'Anonymous User'. This catches two populations it
-- could not:
--
--   * anything an OLDER BUILD wrote after V107 ran. A deployment that advances
--     the schema does not replace every running jar at the same instant, and a
--     stale one writing the old literal is a hazard this project has already met
--     once (a pre-V106 build defaulting reviews.origin to 'contributor').
--   * any other 'anonymous%' spelling, so that the rule is "no byline on an
--     erased rating announces itself" rather than "one exact string is gone".
--
-- SCOPED TO author_erased, and that is load-bearing. Those rows are excluded
-- from idx_reviews_author_manager_role_legacy by its own WHERE clause, so a
-- minted name cannot collide with a live contributor's byline on the same
-- manager, title and employer. Without the scope this statement could fail on
-- that unique index, and it would also be free to rename an ordinary rating that
-- somebody had chosen to sign with the word "Anonymous".
--
-- ILIKE, so 'Anonymous User', 'anonymous user' and 'Anonymous Employee' are all
-- caught. The user's requirement was that no form of it survives anywhere.

UPDATE reviews
   SET author = (ARRAY['Brave','Swift','Bold','Calm','Keen','Wise','Fair','Kind',
                       'Sharp','Quiet','Clear','Warm','Cool','Bright','Loyal'])
                [1 + floor(random() * 15)::int]
             || (ARRAY['Falcon','Tiger','Eagle','Wolf','Bison','Crane','Lynx',
                       'Otter','Raven','Gecko','Heron','Panda','Finch','Moose'])
                [1 + floor(random() * 14)::int]
             || (10 + floor(random() * 90)::int)::text
-- No NULL branch here: reviews.author is NOT NULL, unlike the other two columns,
-- which is why this table's tell was a literal string and theirs was an absence.
 WHERE author_erased
   AND author ILIKE 'anonymous%';
