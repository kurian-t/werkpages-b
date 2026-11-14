-- ============================================================================
-- V97 — the Rev 268 manager-identity redesign, as ONE migration
-- ============================================================================
--
-- WHAT THIS IS, AND WHY IT IS ONE FILE.
--
-- During development this work was written as sixteen migrations, V97 through V112. None of them
-- ever ran in production: the last migration production has applied is V96, added by commit
-- 71d85b7 ("Ability to review company ratings"), and every later file was staged on one developer
-- machine and never committed or deployed.
--
-- Sixteen files that have only ever run against disposable local databases are sixteen separate
-- checksums for production to carry, sixteen entries in flyway_schema_history, and sixteen chances
-- for the ordering problem described in MIGRATION_HISTORY.md section 3 to bite. They were split
-- because they were written on different days, which is a fact about this project's history and
-- not a fact about the schema. Collapsed into one, production applies the redesign as a single
-- transaction that either lands completely or does not land at all.
--
-- THE SECTIONS BELOW ARE THE ORIGINAL FILES, VERBATIM AND IN ORDER. Nothing was rewritten,
-- reordered or merged beyond concatenation. Each banner names the file it came from, so anything
-- that referred to "what V103 did" can still be found. Order is load-bearing throughout: later
-- sections ALTER and CREATE OR REPLACE what earlier ones create, so the sequence is the schema.
--
-- VERIFIED EQUIVALENT: the local database was dropped and rebuilt from V1 with this file in place
-- of the sixteen, and the resulting schema was diffed against the schema the sixteen produced.
-- See WORKLOG_2026-11-10.md for the object counts and the diff result.
--
-- Checked before concatenating: no file contains BEGIN, COMMIT, ROLLBACK, a psql meta-command, or
-- any statement that cannot run inside a transaction (no CREATE INDEX CONCURRENTLY, no VACUUM, no
-- ALTER TYPE ... ADD VALUE). That is what makes a single transaction possible here.
--
-- Rev 268 phases contained: Phase 1 capture adoption (V98), Phase 2 the role, claim, attribution
-- and conflict schema (V99-V103), Phase 4 rating opportunities (V104), identity erasure and its
-- corrections (V105-V109), contribution drafts (V110), testimony and withdrawal (V111) and
-- employment status (V112). V97 itself is the draft autosave that started the chain.
-- ============================================================================



-- ==========================================================================
-- ORIGINALLY V97__draft_autosave.sql
-- ==========================================================================

-- Autosaved partial ratings: one row per form session, newest answer wins.
--
-- The rating forms have always saved to the browser's localStorage on every keystroke; what they
-- did not do was tell the server, so a person who answered four categories and closed the tab left
-- nothing behind. Those four numbers are real observations and are worth keeping, so the forms now
-- sync to captured_drafts while they are being filled in.
--
-- That turns a table written once per submission into one written every few seconds, and the three
-- columns below are what make that safe.

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 1. When it last changed, and which version of the answers this is
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- `revision` is set by the browser and is monotonic within one browser: a millisecond clock
-- reading. It is NOT a trusted value and nothing but ordering depends on it. Two tabs on the same
-- form share a draft token, so without it the tab that happened to reach the server last would
-- win, which is not the same thing as the most recent answer winning.
--
-- The upsert compares it and refuses to go backwards, so an autosave delayed in flight cannot
-- replace newer answers with older ones.
ALTER TABLE captured_drafts ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE captured_drafts ADD COLUMN revision   BIGINT      NOT NULL DEFAULT 0;

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 2. Being filled in, versus left alone
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- A form somebody is typing into right now is not an abandoned rating, and must not appear in the
-- approval queue: an admin would otherwise watch a new item arrive every few seconds and change
-- under them as it was edited.
--
--   'active'    touched within the inactivity window
--   'abandoned' silent for longer than the window, so the queue may show it
--   'resolved'  an admin published or dismissed it; `resolution` says which
--
-- 'abandoned' is REVERSIBLE and that matters: somebody who comes back to the same form resumes
-- the same token, the upsert sets it active again, and it leaves the queue. Abandonment is an
-- observation about silence, not a verdict.
ALTER TABLE captured_drafts ADD COLUMN state TEXT NOT NULL DEFAULT 'abandoned';

ALTER TABLE captured_drafts
    ADD CONSTRAINT captured_drafts_state_known
    CHECK (state IN ('active', 'abandoned', 'resolved'));

COMMENT ON COLUMN captured_drafts.state IS
    'active | abandoned | resolved. Reversible between the first two: a contributor returning to '
    'an unfinished form moves it back to active and out of the approval queue.';

-- Existing rows predate autosave. Every one of them arrived at submit, from somebody who had
-- already walked away, so 'abandoned' is the truthful default and the column default above gives
-- it to them without a backfill.

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- 3. One form session, one row
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- THE constraint this feature rests on. Autosave fires every few seconds, so without a unique
-- token the queue fills with one row per keystroke pause, and the aggregate statistics this data
-- exists to feed would count one person's single opinion dozens of times.
--
-- Deliberately NOT partial. A published or dismissed draft keeps its token so that a late autosave
-- still collides with it and is refused by the upsert's WHERE clause; a partial index would let
-- the row be recreated instead, which is the one failure that silently resurrects a decision an
-- admin already made.
--
-- DEDUPED FIRST, because this runs against production data written by the previous delete-then-
-- insert capture path, which could leave two rows sharing a token if a delete and an insert
-- interleaved. The newest row per token survives, which is the same rule the upsert applies from
-- here on. Without this the CREATE UNIQUE INDEX fails and Werkpages refuses to boot.
DELETE FROM captured_drafts a
 USING captured_drafts b
 WHERE a.draft_token IS NOT NULL
   AND a.draft_token = b.draft_token
   AND (a.created_at, a.id) < (b.created_at, b.id);

-- NOT partial, and that is load-bearing rather than stylistic. ON CONFLICT infers its target
-- from the index, and PostgreSQL will not match a partial one unless the statement repeats the
-- predicate; written partially this failed every autosave with "no unique or exclusion constraint
-- matching the ON CONFLICT specification". The predicate bought nothing either way: NULLs are
-- distinct in a unique index, so a plain one already permits any number of tokenless rows.
CREATE UNIQUE INDEX captured_drafts_token_unique
    ON captured_drafts (draft_token);

-- The queue reads unresolved rows that have gone quiet, newest first.
CREATE INDEX captured_drafts_queue_state
    ON captured_drafts (state, updated_at DESC)
    WHERE reviewed_at IS NULL;


-- ==========================================================================
-- ORIGINALLY V98__capture_attempts.sql
-- ==========================================================================

-- ============================================================================
-- V98 - the capture-attempt ledger.  Schema phase S1 of the manager-identity
-- redesign (design rev 268, "Capture attempts").
--
-- WHAT IT IS FOR
--
-- The add-manager form posts a capture as soon as its first step is valid,
-- before the company box is finished.  One attempt to add "Eddie Junior at
-- Microsoft" therefore left a captured draft at "Mi", and the submission that
-- followed created a second row: two managers for one person, differing only
-- by how much of the company name had been typed.
--
-- That was papered over by adopting whatever row resembled the submission -
-- same name, captured company a prefix of the submitted one, under an hour
-- old.  Resemblance is the thing this redesign exists to remove: the predicate
-- decides that two descriptions mean one person, which is a guess, and a
-- namesake who happened to be captured minutes earlier was adopted by somebody
-- else's submission.
--
-- The ledger answers one question instead - "has this form attempt already
-- been seen" - which is a question about REQUESTS, not about people or jobs.
-- The client mints a token when step one first posts and carries it through
-- the submission, including across the sign-in round trip.  Adoption is then
-- by identity: this attempt's manager, or none.
--
-- TOKENS ARE NEVER CLEARED.  The row's existence is the consumption record,
-- not the state of any column.  An earlier design cleared the token on
-- resolution, so a late or retried request matched nothing and the creation
-- path made a duplicate - through the mechanism built to prevent duplicates.
-- Nothing deletes from this table, including the maintenance sweep.
-- ============================================================================

CREATE TABLE capture_attempts (
  token       UUID PRIMARY KEY,
  manager_id  BIGINT      REFERENCES managers(id) ON DELETE SET NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ,
  outcome     TEXT,
  CONSTRAINT capture_outcome_known
    CHECK (outcome IS NULL OR outcome IN ('submitted','abandoned','discarded','merged')),
  CONSTRAINT capture_resolved_pairs
    CHECK ((resolved_at IS NULL) = (outcome IS NULL))
);

-- manager_id is always supplied by the creating transaction.  It is nullable
-- only so a later deletion of the manager does not erase the record that the
-- attempt happened, which is what ON DELETE SET NULL rather than CASCADE is
-- here for: the attempt stays spent, and a replay of its token recreates
-- nothing.  That is the resurrection the ledger prevents.
CREATE INDEX capture_attempts_manager ON capture_attempts (manager_id);

-- ----------------------------------------------------------------------------
-- The reverse pointer, which is what adoption is keyed on.
--
-- capture_attempts.manager_id says what an attempt produced.  This says which
-- attempt produced a manager, and it is the column the submission matches on,
-- so a captured draft can only ever be adopted by the attempt that created it.
--
-- Nullable, and null for every row that exists today: a manager created by any
-- other path - a search ghost, a queued search, an admin, an approved
-- submission - has no capture attempt behind it and never will.  Adding it
-- nullable is what keeps every existing INSERT in both backends working
-- unchanged (CLAUDE.md section 10).
-- ----------------------------------------------------------------------------
ALTER TABLE managers
  ADD COLUMN capture_token UUID REFERENCES capture_attempts(token);

-- One attempt produces at most one manager, enforced where it belongs rather
-- than left to the application that happens to write it.  Partial, because
-- every pre-existing and every non-capture row is null and those are not
-- duplicates of each other.
CREATE UNIQUE INDEX managers_capture_token
  ON managers (capture_token)
  WHERE capture_token IS NOT NULL;


-- ==========================================================================
-- ORIGINALLY V99__employment_roles.sql
-- ==========================================================================

-- PHASE 2 (S2), migration 1 of 5: career_history becomes the employment-role table.
--
-- Rev 268, "Employment roles". career_history is promoted and renamed IN USE rather
-- than in the table name: a rename migration on a table carrying live foreign keys
-- buys nothing. Where the design says employment_roles, the table is career_history.
--
-- INERT. Nothing in either application writes state, period_known or source yet, and
-- every column added here carries a default that describes what existing rows already
-- are. The one statement that can refuse is the company_id gate, and refusing is its
-- job: see the preflight in db/preflight/phase2_preflight.sql, which an operator runs
-- BEFORE this migration rather than during its window.

-- The title normaliser, defined here because the generated column below is its first
-- use. IMMUTABLE is mandatory: a generated column will not accept a STABLE function,
-- which also rules out anything dictionary-based such as unaccent(), whose dictionary
-- can change under it.
--
-- THE CONTRACT, which matters more than the expression:
--   1. FORMATTING ONLY. Case, whitespace and punctuation are normalised. Letters are
--      never removed.
--   2. NEVER EMPTY for a non-empty input. A key that collapses to '' would make two
--      unrelated titles one employment.
--   3. Stable for the lifetime of the stored column.
--
-- An earlier version was [^a-z0-9]+, which keeps ASCII and nothing else. "Engineering
-- Manager" survived; two different Chinese titles both became '', so two different
-- jobs at one employer on one day were indistinguishable to the uniqueness index
-- below. Unacceptable anywhere, and especially on Werkpages, which carries
-- international data by design.
--
-- [[:alnum:]] is the Unicode-aware class. Rule 2 is ENFORCED by the COALESCE rather
-- than trusted to the class: whatever it matches, a non-empty title keeps a non-empty
-- key.
--
-- CAVEAT. Both [[:alnum:]] and lower() are COLLATION-dependent and this function feeds
-- a STORED generated column, so a collation or ICU upgrade can change what they do and
-- PostgreSQL will not recompute the column. The preflight establishes the behaviour
-- against the real database collation before this runs; the fallback, if it ever
-- proves unstable, is an explicit character map.
CREATE OR REPLACE FUNCTION normalise_title(p_title TEXT)
RETURNS TEXT AS $$
  SELECT COALESCE(
           NULLIF(btrim(regexp_replace(lower(p_title),
                                       '[^[:alnum:]]+', ' ', 'g')), ''),
           btrim(lower(p_title)));
$$ LANGUAGE sql IMMUTABLE;

-- FIRST, before anything else here. start_date has been NOT NULL since V1__init.sql
-- and the new model needs it nullable: career_period_known_has_a_date only means
-- something if null is possible, the unknown-period rows exist to carry "I do not
-- remember when", and without this line every such proposal fails on NOT NULL.
ALTER TABLE career_history ALTER COLUMN start_date DROP NOT NULL;

ALTER TABLE career_history
  ADD COLUMN title_key TEXT GENERATED ALWAYS AS (normalise_title(title)) STORED,

  -- CALENDAR DAYS ARE CANONICAL, and this is the only place that decides it.
  -- career_history.start_date is TIMESTAMPTZ; a contributor's claim carries DATE.
  -- Comparing them directly makes an imported 2023-01-01T00:00:00+00 and a typed
  -- 2023-01-01 the same employment or two different ones depending on the session
  -- timezone, which is not something an employment's identity may depend on.
  --
  -- The column is NOT converted. CLAUDE.md section 10 forbids changing a column's type
  -- on a shared table without a lockstep change in both applications, and both read
  -- start_date today. So the canonical value is a generated calendar day and
  -- everything about identity uses that instead.
  --
  -- AT TIME ZONE 'UTC' is spelled out because a bare ::date on a timestamptz is only
  -- STABLE and a generated column requires IMMUTABLE. It would not create with the
  -- session's zone, which is the correct refusal: the answer must not depend on who is
  -- asking.
  ADD COLUMN start_day DATE GENERATED ALWAYS AS
                            ((start_date AT TIME ZONE 'UTC')::date) STORED,
  ADD COLUMN end_day   DATE GENERATED ALWAYS AS
                            ((end_date   AT TIME ZONE 'UTC')::date) STORED,

  ADD COLUMN period_known  BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN source        TEXT    NOT NULL DEFAULT 'import',
  ADD COLUMN state         TEXT    NOT NULL DEFAULT 'accepted',
  ADD COLUMN rateable      BOOLEAN GENERATED ALWAYS AS (state = 'accepted') STORED,
  ADD COLUMN superseded_by BIGINT  REFERENCES career_history(id),
  -- Set when an admin reopens a rejected role as a fresh proposal, so the rejection
  -- stays on the record and the new row says what it came from.
  ADD COLUMN reopened_from BIGINT  REFERENCES career_history(id);

ALTER TABLE career_history
  ADD CONSTRAINT career_state_known
    CHECK (state IN ('proposed','accepted','rejected','superseded')),
  ADD CONSTRAINT career_superseded_points
    CHECK ((state = 'superseded') = (superseded_by IS NOT NULL)),
  ADD CONSTRAINT career_period_known_has_a_date
    CHECK (period_known = FALSE OR start_date IS NOT NULL),
  -- Rule 2 of the normaliser, enforced rather than assumed. If a future collation
  -- change ever made the expression return '' for a real title this refuses the write
  -- instead of quietly merging two employments.
  ADD CONSTRAINT career_title_key_not_empty CHECK (title_key <> ''),
  ADD CONSTRAINT career_identity UNIQUE (id, manager_id),
  -- B2 FINDING E1: claim_resolved_role_matches (V101) references
  -- (id, manager_id, company_id), and a composite foreign key needs a unique
  -- constraint on EXACTLY its referenced columns. Without this V101 does not create.
  -- Found by running the migration, not by reading it.
  ADD CONSTRAINT career_identity_company UNIQUE (id, manager_id, company_id);

-- EVERY existing row has a start date, because the column was NOT NULL until the
-- statement above. So this is all of them, not some. Left at the default they would
-- all fall outside career_unique_known_employment, and duplicate canonical roles could
-- then be created for the employments it protects.
UPDATE career_history SET period_known = TRUE WHERE start_date IS NOT NULL;

-- Asserted, because a silent miss is invisible until a duplicate appears.
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM career_history WHERE NOT period_known) THEN
    RAISE EXCEPTION 'period_known backfill left rows false';
  END IF;
END $$;

-- company_id must be resolved for every row before this runs. The migration REFUSES
-- rather than degrading: there is exactly one production path, and resolving the
-- stragglers is an operator step that happens BEFORE this migration, not an alternate
-- migration. See the preflight script and HANDOVER.md.
DO $$
DECLARE n BIGINT;
BEGIN
  SELECT count(*) INTO n FROM career_history WHERE company_id IS NULL;
  IF n > 0 THEN
    RAISE EXCEPTION 'V99 refuses: % career_history rows have no company_id. '
                    'Resolve them with the preflight procedure and re-run.', n;
  END IF;
END $$;

ALTER TABLE career_history ALTER COLUMN company_id SET NOT NULL;

-- A SECOND GATE, and it is not about company_id. Existing rows can be distinct under
-- their old representations and collide under the NEW key: two titles that normalise
-- to one title_key, or two timestamps that fall on one UTC calendar day. If any do,
-- CREATE UNIQUE INDEX fails even when every company_id is populated.
DO $$
DECLARE n BIGINT;
BEGIN
  SELECT count(*) INTO n FROM (
    SELECT 1 FROM career_history
     WHERE period_known AND state IN ('proposed','accepted')
     GROUP BY manager_id, company_id, title_key, start_day
    HAVING count(*) > 1) d;
  IF n > 0 THEN
    RAISE EXCEPTION 'V99 refuses: % employment keys hold more than one role '
                    'under the normalised key. Resolve them with the duplicate '
                    'diagnostic in the preflight and re-run.', n;
  END IF;
END $$;

-- Stops two LIVE canonical roles for one employment. Keyed on start_DAY, not
-- start_date, so an imported timestamp and a contributor's calendar date collide when
-- they mean the same day. Partial twice over: unknown periods are outside it, because
-- an unknown date is not evidence of sameness; and so are REJECTED and SUPERSEDED
-- rows, because a rejected record must not occupy the key and silently block a fresh
-- proposal for the same job.
CREATE UNIQUE INDEX career_unique_known_employment
  ON career_history (manager_id, company_id, title_key, start_day)
  WHERE period_known AND state IN ('proposed','accepted');

-- Overlap detection uses the same calendar days. A PLAIN BTREE, not gist.
--
-- A gist index over (manager_id, company_id, daterange(...)) would need CREATE
-- EXTENSION btree_gist to mix the scalar columns with the range, and that is an
-- extension on a shared production database for the benefit of one nightly query over
-- a handful of rows per manager. The sweep reads a manager's accepted roles by this
-- index and tests overlap in SQL.
CREATE INDEX career_period ON career_history (manager_id, company_id, start_day)
  WHERE state = 'accepted';


-- ==========================================================================
-- ORIGINALLY V100__manager_merge_events.sql
-- ==========================================================================

-- PHASE 2 (S2), migration 2 of 5: the merge-event record.
--
-- Rev 268, "Lineage, properly". This is the record of the OPERATION, as distinct from
-- the lineage columns on the rows it moved, which V102 adds.
--
-- IT COMES BEFORE THE CLAIM TABLES, and that ordering is a correction rather than a
-- preference: claim_resolution_events.merge_event_id (V101) references this table, and
-- an earlier allocation put it three migrations later, so V101 could not have created
-- its own foreign key. This table references only managers and users, so it moves
-- freely.
--
-- INERT. Nothing writes it until the merge is rebuilt in phase 3.

CREATE TABLE manager_merge_events (
  id              BIGSERIAL PRIMARY KEY,
  -- Nullable WITH ON DELETE SET NULL, which an earlier version declared NOT NULL
  -- alongside: a contradiction. The denormalised names below are what keep the event
  -- readable, so the ids may go.
  survivor_id     BIGINT      REFERENCES managers(id) ON DELETE SET NULL,
  absorbed_id     BIGINT      REFERENCES managers(id) ON DELETE SET NULL,

  -- absorbed_name is the column that closes the lineage gap. A merge does not rename
  -- anybody, but the absorbed row could later be edited, so recording the name at
  -- merge time means lineage does not depend on that row surviving unchanged.
  survivor_name   TEXT        NOT NULL,
  absorbed_name   TEXT        NOT NULL,
  absorbed_slug   TEXT        NOT NULL,

  roles_moved       INT       NOT NULL,
  roles_superseded  INT       NOT NULL,
  claims_moved      INT       NOT NULL,
  claims_redirected INT       NOT NULL,
  ratings_moved     INT       NOT NULL,
  ratings_held      INT       NOT NULL,

  merged_by       UUID        NOT NULL REFERENCES users(id),
  merged_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX manager_merge_events_absorbed ON manager_merge_events (absorbed_id);
CREATE INDEX manager_merge_events_survivor ON manager_merge_events (survivor_id);


-- ==========================================================================
-- ORIGINALLY V101__role_claims.sql
-- ==========================================================================

-- PHASE 2 (S2), migration 3 of 5: what a contributor asserted, and the audit of how
-- the platform resolved it.
--
-- Rev 268, "Role claims", "Corrections to a finalized claim" and "The claim audit".
--
-- A claim is the contributor's own statement: this person, this employer, this title,
-- these dates. resolved_role_id is the platform's answer to it. The two are kept apart
-- deliberately, and claim_selection_is_honoured_or_canonicalized is what stops a
-- descriptive match from overriding an explicit choice.
--
-- INERT. No application writes this table until phase 4; the triggers here only fire
-- on writes to it.

CREATE TABLE role_claims (
  id            BIGSERIAL PRIMARY KEY,
  -- The ONLY uniqueness on the assertion itself. A claim is never refused or
  -- redirected because it RESEMBLES another claim; proposal_key answers only "have I
  -- already applied this exact request", which is the same question
  -- capture_attempts.token answers for the add-manager form and reviews.draft_token
  -- for a rating.
  proposal_key  UUID        NOT NULL UNIQUE,

  manager_id    BIGINT      NOT NULL REFERENCES managers(id),
  company_id    BIGINT      NOT NULL REFERENCES companies(id),
  title         TEXT        NOT NULL,
  title_key     TEXT        GENERATED ALWAYS AS (normalise_title(title)) STORED,
  start_date    DATE,
  end_date      DATE,
  period_known  BOOLEAN     NOT NULL DEFAULT FALSE,
  -- Separate from a null end_date: "they were still there when I left" and "I do not
  -- know when it ended" are different assertions and only the first is evidence.
  still_there   BOOLEAN     NOT NULL DEFAULT FALSE,

  -- WHAT THE CONTRIBUTOR PICKED. Null means they proposed a new role. Never rewritten
  -- after finalization, by anything, including a merge.
  selected_role_id BIGINT,

  source        TEXT        NOT NULL,
  user_id       UUID        REFERENCES users(id),

  -- Set TOGETHER, at finalization. original_manager_id is the manager the contributor
  -- had selected when they SUBMITTED, not when they first opened the form, which they
  -- may since have changed. Capturing it at first insert was wrong for exactly that
  -- reason.
  finalized_at        TIMESTAMPTZ,
  original_manager_id BIGINT REFERENCES managers(id),

  revision      BIGINT      NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- WHAT THE PLATFORM RESOLVED. Equals selected_role_id unless an audited
  -- canonicalization has redirected it.
  resolved_role_id BIGINT,
  resolved_at      TIMESTAMPTZ,
  resolved_by      UUID     REFERENCES users(id),
  resolution       TEXT,

  -- The audit of a legitimate redirection. A merge sets all three.
  canonicalized_at   TIMESTAMPTZ,
  canonicalized_by   UUID   REFERENCES users(id),
  canonicalized_from BIGINT REFERENCES career_history(id),

  -- WHAT THIS IS A DUPLICATE OF. A claim marked duplicate has its resolved_role_id set
  -- to NULL, so without this column a returning contributor whose claim was already
  -- marked duplicate has nothing to be sent to: the reclaim path looks them up by
  -- (user_id, resolved_role_id) and that is null by then.
  duplicate_of_claim_id BIGINT REFERENCES role_claims(id),

  CONSTRAINT claim_source_known
    CHECK (source IN ('contributor','admin','import')),
  CONSTRAINT claim_resolution_known
    CHECK (resolution IS NULL OR resolution IN ('linked','created','rejected','duplicate')),
  CONSTRAINT claim_resolution_pairs
    CHECK ((resolved_at IS NULL) = (resolution IS NULL)),
  CONSTRAINT claim_linked_has_a_role
    CHECK (resolution IS NULL OR resolution NOT IN ('linked','created')
           OR resolved_role_id IS NOT NULL),
  CONSTRAINT claim_unlinked_has_no_role
    CHECK (resolution IS NULL OR resolution NOT IN ('rejected','duplicate')
           OR resolved_role_id IS NULL),
  -- A duplicate names its target; a rejection has none to name.
  --
  -- IS NOT DISTINCT FROM, not =. With resolution NULL and a target set,
  -- (NULL = 'duplicate') is NULL, so the whole comparison is NULL, and a CHECK ACCEPTS
  -- NULL. The = form therefore required a target when marking duplicate but did NOT
  -- forbid a target on an unresolved claim. Both directions have to be two-valued.
  CONSTRAINT claim_duplicate_names_its_target
    CHECK ((resolution IS NOT DISTINCT FROM 'duplicate')
           = (duplicate_of_claim_id IS NOT NULL)),
  CONSTRAINT claim_not_its_own_duplicate
    CHECK (duplicate_of_claim_id IS NULL OR duplicate_of_claim_id <> id),

  -- A SELECTION IS HONOURED, OR ITS REDIRECTION IS AUDITED.
  -- A submission may never resolve to a role other than the one picked. A merge may,
  -- and when it does it says so and names where it came from.
  CONSTRAINT claim_selection_is_honoured_or_canonicalized
    CHECK (selected_role_id IS NULL
           OR resolved_role_id IS NULL
           OR resolved_role_id = selected_role_id
           OR canonicalized_at IS NOT NULL),
  CONSTRAINT claim_canonicalization_is_complete
    CHECK ((canonicalized_at IS NULL) = (canonicalized_by IS NULL)
       AND (canonicalized_at IS NULL) = (canonicalized_from IS NULL)),

  -- Finalization fixes the submission's identity. Both or neither.
  CONSTRAINT claim_finalization_sets_lineage
    CHECK ((finalized_at IS NULL) = (original_manager_id IS NULL)),
  CONSTRAINT claim_resolved_is_finalized
    CHECK (resolved_at IS NULL OR finalized_at IS NOT NULL),
  CONSTRAINT claim_period_known_has_a_date
    CHECK (period_known = FALSE OR start_date IS NOT NULL),

  -- The key reviews.claim_id reaches through, so a rating and its claim cannot
  -- disagree about which manager they concern.
  CONSTRAINT claim_identity UNIQUE (id, manager_id)
);

-- The RESOLVED role must belong to the claim's CURRENT manager AND company. Manager
-- consistency alone allowed a claim about company Z to resolve to a company X role,
-- which would have counted a rating against the wrong employer: the exact failure the
-- analytics correction exists to prevent.
ALTER TABLE role_claims ADD CONSTRAINT claim_resolved_role_matches
  FOREIGN KEY (resolved_role_id, manager_id, company_id)
  REFERENCES career_history (id, manager_id, company_id)
  DEFERRABLE INITIALLY IMMEDIATE;

-- The SELECTED role is historical and may predate a merge, so it is referenced by id
-- ALONE and deliberately so: after a merge the claim's manager_id is the survivor
-- while the selected role may still carry the absorbed profile, and a composite key
-- would refuse a perfectly legitimate state. A selection is a historical fact about a
-- row that existed at a moment in time; the integrity that matters is enforced on
-- resolved_role_id, which is what analytics and publication read.
ALTER TABLE role_claims ADD CONSTRAINT claim_selected_role_exists
  FOREIGN KEY (selected_role_id) REFERENCES career_history (id);

-- Where "one rating per contributor per role" now lives. It is on claims rather than
-- reviews because reviews no longer name a role. A signed-in contributor submitting
-- twice for the same job produces a second claim that cannot resolve to the same role,
-- so it resolves as duplicate and its rating is held: preserved, not refused.
--
-- Anonymous claims sit outside this index deliberately. There is no identity to key
-- on, and the capture token plus the hashed-address quota are request idempotency and
-- abuse limits, not per-person uniqueness.
CREATE UNIQUE INDEX role_claims_one_per_contributor_per_role
  ON role_claims (user_id, resolved_role_id)
  WHERE user_id IS NOT NULL AND resolved_role_id IS NOT NULL;

CREATE INDEX role_claims_unresolved
  ON role_claims (manager_id, company_id, title_key) WHERE resolved_at IS NULL;
CREATE INDEX role_claims_role     ON role_claims (resolved_role_id);
CREATE INDEX role_claims_lineage  ON role_claims (original_manager_id);
CREATE INDEX role_claims_dup      ON role_claims (duplicate_of_claim_id);

-- An admin correcting a finalized claim writes the new value and a row here recording
-- what it was. manager_id is deliberately absent from the permitted fields: reassigning
-- a saved claim to a different person is not a correction, it is a new contribution.
CREATE TABLE claim_corrections (
  id           BIGSERIAL   PRIMARY KEY,
  claim_id     BIGINT      NOT NULL REFERENCES role_claims(id),
  field        TEXT        NOT NULL,
  was          TEXT,
  now_is       TEXT,
  reason       TEXT        NOT NULL,
  corrected_by UUID        NOT NULL REFERENCES users(id),
  corrected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT correction_field_known
    CHECK (field IN ('company_id','title','start_date','end_date','period_known'))
);

CREATE INDEX claim_corrections_claim ON claim_corrections (claim_id, id);

-- THE RESOLUTION AUDIT. Five event kinds, because a roleless rejection is a real
-- transition that none of the other four can express: an admin rejects an unresolved
-- claim, resolution goes NULL -> 'rejected', and resolved_role_id was null before and
-- after. The append-only history had a hole exactly where every administrative
-- rejection should have been.
CREATE TABLE claim_resolution_events (
  id             BIGSERIAL   PRIMARY KEY,
  claim_id       BIGINT      NOT NULL REFERENCES role_claims(id) ON DELETE RESTRICT,
  event          TEXT        NOT NULL,
  from_role_id   BIGINT      REFERENCES career_history(id) ON DELETE RESTRICT,
  to_role_id     BIGINT      REFERENCES career_history(id) ON DELETE RESTRICT,
  merge_event_id BIGINT      REFERENCES manager_merge_events(id) ON DELETE RESTRICT,
  resolution     TEXT,       -- the claim's resolution after this event
  reason         TEXT,
  at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  by             UUID        REFERENCES users(id),

  CONSTRAINT claim_event_known CHECK (event IN
    ('resolved','redirected','unlinked','corrected','refused')),
  -- a first resolution: nothing before, something after
  CONSTRAINT claim_event_resolved   CHECK (event <> 'resolved'
    OR (from_role_id IS NULL     AND to_role_id IS NOT NULL)),
  -- a merge redirecting to an equivalent role: both sides, and they differ
  CONSTRAINT claim_event_redirected CHECK (event <> 'redirected'
    OR (from_role_id IS NOT NULL AND to_role_id IS NOT NULL
        AND from_role_id <> to_role_id)),
  -- a merge marking a claim duplicate: a source, and DELIBERATELY no destination.
  -- This is the transition the first design could not record, which made a merge
  -- that unlinked a duplicate fail its own audit.
  CONSTRAINT claim_event_unlinked   CHECK (event <> 'unlinked'
    OR (from_role_id IS NOT NULL AND to_role_id IS NULL)),
  -- an admin correcting an attribution
  CONSTRAINT claim_event_corrected  CHECK (event <> 'corrected'
    OR to_role_id IS NOT NULL),
  -- a terminal decision on a claim that never resolved to anything
  CONSTRAINT claim_event_refused    CHECK (event <> 'refused'
    OR (from_role_id IS NULL AND to_role_id IS NULL
        AND resolution IS NOT NULL))
);

-- By id and not by at: one transaction can write several of these events, now() is
-- identical across all of them, and ordering by the timestamp alone therefore leaves
-- the sequence undefined in exactly the case that needs it, a claim redirected twice
-- by one merge.
CREATE INDEX claim_resolution_events_claim
  ON claim_resolution_events (claim_id, id);

-- Written by a trigger, so no code path can change a resolution without leaving the
-- record. The event kind is DERIVED from the transition, and so is the ACTOR.
--
-- The actor matters and the first version got it wrong: it took `by` from
-- NEW.resolved_by for every event. A merge redirection leaves resolved_by as it was
-- and sets canonicalized_by to the administrator doing the merge, so every redirection
-- was attributed to whoever originally resolved the claim.
CREATE OR REPLACE FUNCTION record_claim_resolution() RETURNS trigger AS $$
DECLARE v_event TEXT; v_actor UUID; v_merge BIGINT;
BEGIN
  -- Set by the merge transaction, which writes its event row FIRST so this foreign key
  -- resolves. Null outside a merge.
  --
  -- NULLIF, and it is not defensive clutter. A custom GUC set with set_config(..., is_local =>
  -- true) does not revert to NULL when the transaction ends: it reverts to its RESET value, which
  -- for a setting that was never set at session level is the EMPTY STRING. So on a pooled
  -- connection that has served one merge, every later claim resolution read '' and died with
  --   ERROR: invalid input syntax for type bigint: "" (22P02)
  -- which is a production failure after the FIRST merge, on a connection the pool keeps handing
  -- out. Found by running the merge twice against a pool rather than by reading the statement.
  v_merge := NULLIF(current_setting('rmm.merge_event_id', true), '')::BIGINT;

  v_event := CASE
               -- FIRST, because a roleless refusal has null on both sides and would
               -- otherwise be classified 'resolved' and fail its CHECK.
               WHEN OLD.resolved_role_id IS NULL
                AND NEW.resolved_role_id IS NULL THEN 'refused'
               WHEN OLD.resolved_role_id IS NULL THEN 'resolved'
               WHEN NEW.resolved_role_id IS NULL THEN 'unlinked'
               -- NOT a timestamp comparison. now() is the TRANSACTION's timestamp, so
               -- two redirections inside one merge leave canonicalized_at identical
               -- and the second was classified 'corrected' with the wrong actor.
               -- canonicalized_from is set to the role the claim is moving OFF, so it
               -- equals OLD.resolved_role_id exactly when this is a redirection.
               WHEN NEW.canonicalized_from IS NOT NULL
                AND NEW.canonicalized_from = OLD.resolved_role_id
                                                 THEN 'redirected'
               ELSE 'corrected'
             END;

  v_actor := CASE v_event
               WHEN 'redirected' THEN NEW.canonicalized_by
               ELSE NEW.resolved_by
             END;

  INSERT INTO claim_resolution_events
         (claim_id, event, from_role_id, to_role_id, merge_event_id,
          resolution, by, reason)
  VALUES (NEW.id, v_event, OLD.resolved_role_id, NEW.resolved_role_id,
          v_merge, NEW.resolution, v_actor,
          -- A null actor is not a gap in the record, it is the record: the platform
          -- resolved this with nobody deciding.
          CASE WHEN v_merge IS NOT NULL THEN 'manager_merge'
               WHEN v_actor IS NULL     THEN 'automatic'
               ELSE current_setting('rmm.resolution_reason', true)
          END);
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

-- RESOLUTION OR ROLE, not role alone. The first version fired only on
-- resolved_role_id, so an admin rejecting an unresolved claim changed its resolution
-- and wrote no event.
--
-- One fire per row per UPDATE, so a statement that changes both still writes exactly
-- one event.
CREATE TRIGGER claim_resolution_is_recorded
  AFTER UPDATE OF resolved_role_id, resolution ON role_claims
  FOR EACH ROW WHEN (NEW.resolved_role_id IS DISTINCT FROM OLD.resolved_role_id
                  OR NEW.resolution      IS DISTINCT FROM OLD.resolution)
  EXECUTE FUNCTION record_claim_resolution();

-- Append-only in the strict sense. A rule with DO INSTEAD NOTHING would swallow the
-- attempt silently, which is worse than no protection: raise. Reused by V103 for
-- review_supersessions.
CREATE OR REPLACE FUNCTION refuse_history_change() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'this table is append-only'; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER claim_resolution_events_append_only
  BEFORE UPDATE OR DELETE ON claim_resolution_events
  FOR EACH ROW EXECUTE FUNCTION refuse_history_change();


-- ==========================================================================
-- ORIGINALLY V102__review_role_attribution.sql
-- ==========================================================================

-- PHASE 2 (S3), migration 4 of 5: a rating's employment, its employer and its lineage.
--
-- Rev 268, "Reviews", "The invariant chain" and "Original identity".
--
-- NO role_id. The role is reached through the claim, so there is exactly one answer to
-- "which employment does this rating concern" and nothing to disagree with it.
--
-- INERT. Every column added here carries a default that describes what a pre-cutover
-- rating already is: role_basis 'legacy', attribution_basis 'none', role_required
-- FALSE, claim_id null. An INSERT written by either backend today names none of them
-- and still satisfies every constraint below. That is asserted rather than assumed,
-- by Phase2SchemaCompatibilityIntegrationTest in both repositories.
--
-- Between this migration and V103 the reviews table carries claim_id with NO
-- publication enforcement, because the predicate reads role_conflicts and cannot be
-- created before it. That is safe only because no application writes claims until
-- phase 4: it is a real ordering constraint on the deployment, not an incidental
-- detail.

ALTER TABLE reviews
  ADD COLUMN claim_id      BIGINT,
  ADD COLUMN role_required BOOLEAN NOT NULL DEFAULT FALSE,
  -- Legacy rows only. A role-bearing rating derives its employer from its claim's
  -- role, so storing it here too would be a second source of truth.
  ADD COLUMN attributed_company_id BIGINT REFERENCES companies(id),
  ADD COLUMN attribution_basis TEXT NOT NULL DEFAULT 'none',

  -- HOW MUCH THE LINEAGE IS WORTH. original_manager_id on a legacy row is back-filled
  -- from manager_id, and that is the CURRENT association, not a verified original:
  -- merges have already happened in production, and a review sitting on a survivor
  -- today may have arrived from a profile absorbed before any of this existed.
  -- Labelling that 'submitted' would be a fabrication with a column name attached.
  ADD COLUMN original_manager_id BIGINT REFERENCES managers(id),
  ADD COLUMN lineage_basis TEXT NOT NULL DEFAULT 'unknown',

  -- WHERE THIS RATING'S EMPLOYMENT COMES FROM. Three provenances, and they are NOT
  -- interchangeable, which role_required as a boolean could not say.
  --
  --   'claimed'   a claim names the role; claim_id IS NOT NULL
  --   'legacy'    pre-cutover, no claim, employment known only from the manager_title
  --               and manager_company strings
  --   'withdrawn' the author withdrew it: no claim, but the role is known exactly,
  --               through withdrawn_role_id
  --
  -- The legacy uniqueness indexes key on 'legacy' ALONE. Overloading
  -- role_required = FALSE put withdrawn ratings into those indexes, where two
  -- withdrawals by one author of two DISTINCT employment periods at one employer
  -- under one title collide on (author, manager, title, company) and the second
  -- withdrawal is refused. Somebody withdrawing their own rating must never be told
  -- no.
  ADD COLUMN role_basis TEXT NOT NULL DEFAULT 'legacy',
  ADD COLUMN withdrawn_role_id BIGINT REFERENCES career_history(id),

  -- What this rating collided with during a merge, and what a human decided about it.
  -- The row is always KEPT; the STATE is what takes it out of the legacy unique
  -- indexes, which is what lets a merge preserve both contributions.
  --
  -- Three states rather than a boolean, because an author-string collision is NOT
  -- evidence of the same person. A moderator who decides two ratings came from
  -- different people needs a way to say so that leaves both public, and
  -- 'cleared_distinct' is it: adjudicated, live, and permanently outside the legacy
  -- index because a human has answered the question that index was guessing at.
  ADD COLUMN merge_duplicate_of UUID REFERENCES reviews(id),
  ADD COLUMN legacy_dedup_state TEXT NOT NULL DEFAULT 'indexed',

  ADD CONSTRAINT reviews_role_basis_known
    CHECK (role_basis IN ('claimed','legacy','withdrawn')),
  ADD CONSTRAINT reviews_basis_matches_claim
    CHECK ((role_basis = 'claimed') = (claim_id IS NOT NULL)),

  -- A WITHDRAWN RATING KEEPS ITS ROLE WHEN THERE WAS ONE TO KEEP.
  --
  -- A biconditional here forbade withdrawing a rating whose claim had no resolved
  -- role. Unresolved, rejected and duplicate claims are all valid states in this
  -- design and their ratings are held rather than deleted, so that rule meant the only
  -- contributors who could not withdraw were the ones whose employment the platform
  -- had not accepted.
  --
  -- The rule now: a role implies the withdrawn basis, and the withdrawn basis WITHOUT
  -- a role is permitted only while the rating is not live. IS DISTINCT FROM, because a
  -- CHECK accepts NULL and <> would let a null disposition through.
  ADD CONSTRAINT reviews_withdrawn_keeps_its_role CHECK (
    CASE
      WHEN withdrawn_role_id IS NOT NULL THEN role_basis = 'withdrawn'
      WHEN role_basis = 'withdrawn'      THEN disposition IS DISTINCT FROM 'live'
      ELSE TRUE
    END),

  ADD CONSTRAINT reviews_attribution_known
    CHECK (attribution_basis IN
           ('role','withdrawn_role','submitted','unresolved','none')),
  -- ATTRIBUTION FOLLOWS PROVENANCE, and the two cannot disagree. Without this a
  -- withdrawn rating could carry attribution_basis 'role', which the company
  -- projection would then try to reach through a claim it no longer has, and it would
  -- silently vanish from its employer's figures while still counting in its
  -- manager's.
  ADD CONSTRAINT reviews_attribution_follows_basis CHECK (
    CASE role_basis
      WHEN 'claimed'   THEN attribution_basis = 'role'
      -- 'unresolved' when there was no role to attribute to, which is the same value
      -- a legacy rating with an unknown employer carries.
      WHEN 'withdrawn' THEN attribution_basis =
           CASE WHEN withdrawn_role_id IS NOT NULL THEN 'withdrawn_role'
                ELSE 'unresolved' END
      WHEN 'legacy'    THEN attribution_basis IN ('submitted','unresolved','none')
    END),
  -- role_required is now derivable from role_basis, so they are tied rather than left
  -- free to drift. The S4 exemption machinery still reads role_required; nothing reads
  -- it to decide publication any more.
  ADD CONSTRAINT reviews_role_required_follows_basis
    CHECK (role_required = (role_basis = 'claimed')),
  ADD CONSTRAINT reviews_role_basis_stores_nothing
    CHECK (attribution_basis <> 'role' OR attributed_company_id IS NULL),
  ADD CONSTRAINT reviews_lineage_basis_known
    CHECK (lineage_basis IN ('submitted','current_association','unknown')),
  ADD CONSTRAINT reviews_legacy_dedup_known
    CHECK (legacy_dedup_state IN ('indexed','held_duplicate','cleared_distinct')),
  -- In the index exactly when it has collided with nothing.
  ADD CONSTRAINT reviews_dedup_state_pairs
    CHECK ((legacy_dedup_state = 'indexed') = (merge_duplicate_of IS NULL));

-- THE LEGACY BACKFILL, in one pass.
--
-- The design specifies three separate statements: role_required FALSE, then
-- original_manager_id from manager_id, then the lineage classification. They are
-- combined here because each is a full-table UPDATE and reviews is the largest table
-- in this database: three passes rewrite every row three times for a result one pass
-- produces. Nothing about the outcome differs, and the assertion below proves it.
--
-- role_required is already FALSE by the column default, so that assignment is a no-op
-- kept for the record that the legacy exemption is set deliberately rather than
-- inherited from a default somebody could later change.
--
-- lineage_basis: 'current_association' when this manager has never absorbed another
-- profile, because no merge can then have moved the rating and the current manager is
-- almost certainly the submitted one. 'unknown' when the manager IS a merge target,
-- because nothing records which ratings moved. Never 'submitted': only a claim's
-- finalization can record that, and no legacy rating has one.
UPDATE reviews r
   SET role_required       = FALSE,
       original_manager_id = r.manager_id,
       lineage_basis       = CASE
         WHEN EXISTS (SELECT 1 FROM managers m WHERE m.merged_into = r.manager_id)
           THEN 'unknown' ELSE 'current_association' END;

DO $$
DECLARE n BIGINT;
BEGIN
  SELECT count(*) INTO n FROM reviews
   WHERE original_manager_id IS NULL OR original_manager_id <> manager_id;
  IF n > 0 THEN
    RAISE EXCEPTION 'V102 lineage backfill left % rows wrong', n;
  END IF;
END $$;

ALTER TABLE reviews ADD CONSTRAINT reviews_role_present
  CHECK (role_required = FALSE OR claim_id IS NOT NULL) NOT VALID;
ALTER TABLE reviews VALIDATE CONSTRAINT reviews_role_present;

-- A rating's claim must belong to the rating's OWN manager.
ALTER TABLE reviews ADD CONSTRAINT reviews_claim_is_the_managers
  FOREIGN KEY (claim_id, manager_id)
  REFERENCES role_claims (id, manager_id)
  DEFERRABLE INITIALLY IMMEDIATE;

-- A WITHDRAWN RATING'S ROLE MUST BELONG TO ITS OWN MANAGER.
--
-- A plain foreign key on withdrawn_role_id alone proves only that the role exists. It
-- permits repointing a withdrawn rating at an accepted role on a DIFFERENT manager,
-- which attributes it to the wrong employer while leaving it on the original
-- manager's profile: the two public surfaces would then disagree about the same
-- rating.
--
-- DEFERRABLE because a merge moves reviews.manager_id and career_history.manager_id in
-- different statements, so the merge SETs this DEFERRED alongside the other two
-- composites.
ALTER TABLE reviews ADD CONSTRAINT reviews_withdrawn_role_is_the_managers
  FOREIGN KEY (withdrawn_role_id, manager_id)
  REFERENCES career_history (id, manager_id)
  DEFERRABLE INITIALLY IMMEDIATE;

-- ONE RATING PER CLAIM, deferred. The author-reclaim transaction inserts the signed
-- rating BEFORE deleting the anonymous one, deliberately, so that a failed insert
-- cannot destroy a rating that already exists; both reference the adopted claim for
-- the length of that transaction. Immediate enforcement would force
-- delete-before-insert and reintroduce exactly the hazard the company path rejected in
-- production.
--
-- A UNIQUE constraint permits many NULLs, so every legacy row with a null claim_id is
-- unaffected. It is deferrable, and a deferrable unique constraint cannot be an
-- ON CONFLICT inference target; nothing upserts on claim_id, so that costs nothing.
ALTER TABLE reviews ADD CONSTRAINT reviews_one_per_claim
  UNIQUE (claim_id) DEFERRABLE INITIALLY DEFERRED;

-- THE EXISTING UNIQUENESS INDEXES ARE MIGRATED, NOT MERELY ADDED TO.
--
-- V37 keyed contributor uniqueness on the manager_title and manager_company STRINGS.
-- The new model puts it on role_claims (user_id, resolved_role_id). Leaving the old
-- indexes in place would defeat the central new capability: a contributor who
-- legitimately rated two distinct employment periods with the same title at the same
-- employer would be refused by an index that cannot tell the two periods apart.
--
-- They are narrowed to legacy rows rather than dropped, so existing protection is kept
-- exactly where it still applies.
DROP INDEX idx_reviews_user_manager_role;
DROP INDEX idx_reviews_author_manager_role;

-- role_basis = 'legacy' identifies the pre-claim era. NOT role_required = FALSE, which
-- is also true of an author-withdrawn rating and would drag it into these indexes.
--
-- merge_duplicate_of IS NULL, through legacy_dedup_state, is the second half, and it
-- is what makes a merge possible at all. These indexes do not mention disposition, so
-- holding a rating does not take it out of them: two legacy ratings by one contributor
-- of two profiles that are now one person collide on the UPDATE that moves manager_id,
-- and the merge fails before it can reconcile anything. Marking the loser excludes it
-- from the index while keeping the row.
CREATE UNIQUE INDEX idx_reviews_user_manager_role_legacy
  ON reviews (user_id, manager_id,
              lower(trim(coalesce(manager_title, ''))),
              lower(trim(coalesce(manager_company, ''))))
  WHERE user_id IS NOT NULL AND weight = FALSE
    AND role_basis = 'legacy'
    AND legacy_dedup_state = 'indexed';

-- NOTE THE ASYMMETRY, because the collision detector has to match it exactly. The
-- user-keyed index applies only to signed-in ratings. The author-keyed one applies to
-- EVERY legacy rating regardless of user_id, so it can reject a pair the first index
-- would have allowed: two different accounts sharing a display name, or a signed-in
-- and an anonymous rating sharing one.
CREATE UNIQUE INDEX idx_reviews_author_manager_role_legacy
  ON reviews (lower(trim(author)), manager_id,
              lower(trim(coalesce(manager_title, ''))),
              lower(trim(coalesce(manager_company, ''))))
  WHERE weight = FALSE
    AND role_basis = 'legacy'
    AND legacy_dedup_state = 'indexed';

-- The route from a claim to its ratings, which the publication triggers V103 adds
-- walk on every claim and role transition.
CREATE INDEX reviews_claim ON reviews (claim_id) WHERE claim_id IS NOT NULL;
-- The withdrawn route, which the role trigger, the restoration sweep and the company
-- projection all read.
CREATE INDEX reviews_withdrawn_role ON reviews (withdrawn_role_id)
  WHERE withdrawn_role_id IS NOT NULL;


-- ==========================================================================
-- ORIGINALLY V103__role_conflicts_and_publication.sql
-- ==========================================================================

-- PHASE 2 (S3), migration 5 of 5: the moderation tasks, and the one publication rule.
--
-- Rev 268, "Grouping without losing the claims", "What a foreign key cannot say",
-- "Supersession" and "Observability".
--
-- rating_is_publishable and its five triggers live HERE rather than with the reviews
-- columns, because the predicate reads role_conflicts and role_conflict_claims and
-- cannot be created in a migration that precedes them.
--
-- INERT. No application writes a conflict, a supersession or a sweep run until phase
-- 3 at the earliest. The five publication triggers DO fire on ordinary review writes
-- from today onwards, and they are deliberately total: for a rating with role_basis
-- 'legacy', which is every existing row and every row either backend writes now,
-- rating_role_is_publishable returns TRUE on its first branch without reading another
-- table.

-- WHICH KINDS DECIDE PUBLICATION. Through a function, not an inline list, because the
-- sweep guard needs the same answer in a BEFORE trigger where the generated column's
-- value is not yet available. Two copies of this list would drift; one function
-- cannot.
CREATE OR REPLACE FUNCTION kind_blocks_publication(p_kind TEXT)
RETURNS boolean AS $$
  SELECT p_kind IN ('ambiguous_resolution','possible_duplicate',
                    'duplicate_rating_on_merge','cross_era_possible_duplicate');
$$ LANGUAGE sql IMMUTABLE;

CREATE TABLE role_conflicts (
  id            BIGSERIAL PRIMARY KEY,
  kind          TEXT        NOT NULL,

  -- The DISPUTE's identity, not the roles'. Two unrelated unresolved disputes both
  -- have null role ids; keying on those grouped them together, so two contributors
  -- disputing one manager's job and two disputing another's shared a queue item.
  manager_id    BIGINT      REFERENCES managers(id)  ON DELETE SET NULL,
  company_id    BIGINT      REFERENCES companies(id) ON DELETE SET NULL,
  title_key     TEXT        NOT NULL,
  period_key    TEXT        NOT NULL,   -- start_date, or 'unknown'
  case_key      TEXT        GENERATED ALWAYS AS (
                              COALESCE(manager_id::text,'?') || ':' ||
                              COALESCE(company_id::text,'?') || ':' ||
                              title_key || ':' || period_key) STORED,

  role_id       BIGINT      REFERENCES career_history(id) ON DELETE SET NULL,
  other_role_id BIGINT      REFERENCES career_history(id) ON DELETE SET NULL,

  -- Denormalised so the evidence survives its parents. Written once. CASCADE was
  -- wrong in a system whose first principle is preserving history: deleting a manager
  -- would have erased the record that their employment was ever disputed.
  manager_name  TEXT        NOT NULL,
  company_name  TEXT        NOT NULL,
  title         TEXT        NOT NULL,
  period        TEXT,

  detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at   TIMESTAMPTZ,
  resolved_by   UUID        REFERENCES users(id),
  resolution    TEXT,
  note          TEXT,

  -- Where this task went when a manager merge folded it into another. Set together
  -- with resolution 'merged', so the old task stays readable and says what replaced it
  -- rather than vanishing.
  superseded_by_conflict_id BIGINT REFERENCES role_conflicts(id),

  -- Derived from the kind rather than set by whoever inserts the row. A blocking kind
  -- means "we do not know which employment this rating belongs to"; everything else is
  -- enrichment that an admin reads and that never holds a rating.
  --
  -- Changing the function later does NOT recompute existing rows: a stored generated
  -- column is written once. Adding a blocking kind therefore needs an explicit
  -- backfill, and that is a migration, not an edit.
  blocks_publication BOOLEAN GENERATED ALWAYS AS
                       (kind_blocks_publication(kind)) STORED,

  CONSTRAINT role_conflict_kind_known CHECK (kind IN
    ('end_date_disagreement','ambiguous_resolution','unknown_period_duplicate',
     'overlapping_period','correction_candidate','late_edit_to_accepted',
     'duplicate_rating_on_merge','possible_duplicate',
     'cross_era_possible_duplicate','previously_rejected')),
  CONSTRAINT role_conflict_resolution_known CHECK (resolution IS NULL OR resolution IN
    ('merged','kept_separate','corrected','dismissed','superseded_the_legacy')),
  CONSTRAINT role_conflict_merged_points CHECK
    (superseded_by_conflict_id IS NULL OR resolution = 'merged'),
  CONSTRAINT role_conflict_resolved_pairs
    CHECK ((resolved_at IS NULL) = (resolution IS NULL))
);

-- One OPEN task per disputed EMPLOYMENT per kind. Partial, so a genuine recurrence
-- after a resolution opens a new task.
CREATE UNIQUE INDEX role_conflicts_one_open
  ON role_conflicts (case_key, kind) WHERE resolved_at IS NULL;

-- The individual assertions behind one task. This is the evidence, and RESTRICT
-- because it must outlive the moderation convenience above: nothing in this design
-- deletes a conflict or a claim, and RESTRICT makes that structural rather than a
-- convention somebody later forgets.
CREATE TABLE role_conflict_claims (
  conflict_id BIGINT NOT NULL REFERENCES role_conflicts(id) ON DELETE RESTRICT,
  claim_id    BIGINT NOT NULL REFERENCES role_claims(id)    ON DELETE RESTRICT,

  -- SUBJECT OR EVIDENCE, and this column is what stops two correct rules from
  -- contradicting each other.
  --
  -- A blocking conflict holds the rating of its SUBJECT. Everything else attached to
  -- it is EVIDENCE: context a moderator needs, and no statement that the evidence is
  -- itself in doubt.
  --
  -- Without the distinction a merge could not commit. It holds the absorbed duplicate,
  -- keeps the survivor live, and links BOTH claims to one duplicate_rating_on_merge
  -- task. That kind blocks publication, so linking the survivor made
  -- rating_is_publishable() refuse its still-live rating and the merge failed at
  -- COMMIT having done everything right.
  role        TEXT   NOT NULL DEFAULT 'subject',

  added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (conflict_id, claim_id),
  CONSTRAINT role_conflict_claim_role_known CHECK (role IN ('subject','evidence'))
);

-- The predicate reads this, so it is the hot path.
CREATE INDEX role_conflict_claims_subject
  ON role_conflict_claims (claim_id) WHERE role = 'subject';
CREATE INDEX role_conflict_claims_claim ON role_conflict_claims (claim_id);

-- A BLOCKING CONFLICT MUST HAVE A SUBJECT. One with only evidence holds nothing and is
-- an enrichment task wearing a blocking kind, which is a bug rather than a state to
-- tolerate. Deferred, because a transaction legitimately inserts the task and its
-- links in either order.
--
-- BOTH SIDES OF THE MEMBERSHIP, which is the correction that matters. A first version
-- read NEW.conflict_id only and fired on INSERT and UPDATE OF role, so deleting the
-- last subject left an open blocking conflict holding nothing, and moving a subject to
-- another conflict left the one it LEFT subjectless. Neither fired a check, and the
-- invariant was announced rather than enforced.
CREATE OR REPLACE FUNCTION assert_blocking_has_a_subject() RETURNS trigger AS $$
DECLARE v_ids BIGINT[]; v_id BIGINT;
BEGIN
  -- One branch per operation: a CASE over OLD and NEW resolves both operands, and
  -- only one of them exists on an INSERT or a DELETE.
  IF    TG_TABLE_NAME = 'role_conflicts' THEN v_ids := ARRAY[NEW.id];
  ELSIF TG_OP = 'INSERT'                 THEN v_ids := ARRAY[NEW.conflict_id];
  ELSIF TG_OP = 'DELETE'                 THEN v_ids := ARRAY[OLD.conflict_id];
  ELSE
    -- A reassignment: both the conflict it left and the one it joined, since either
    -- can be the one left without a subject.
    v_ids := ARRAY(SELECT DISTINCT x FROM unnest(
                     ARRAY[OLD.conflict_id, NEW.conflict_id]) AS t(x));
  END IF;

  FOREACH v_id IN ARRAY v_ids LOOP
    IF EXISTS (SELECT 1 FROM role_conflicts cf
                WHERE cf.id = v_id AND cf.blocks_publication
                  AND cf.resolved_at IS NULL)
       AND NOT EXISTS (SELECT 1 FROM role_conflict_claims rcc
                        WHERE rcc.conflict_id = v_id
                          AND rcc.role = 'subject') THEN
      RAISE EXCEPTION 'blocking conflict % has no subject claim', v_id;
    END IF;
  END LOOP;
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER conflicts_blocking_has_a_subject
  AFTER INSERT OR UPDATE OF kind, resolved_at ON role_conflicts
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_blocking_has_a_subject();

-- INSERT OR UPDATE OR DELETE, not UPDATE OF role. A membership can leave a conflict by
-- being deleted or by being repointed, and both are ways to remove its last subject.
CREATE CONSTRAINT TRIGGER conflict_claims_blocking_has_a_subject
  AFTER INSERT OR UPDATE OR DELETE ON role_conflict_claims
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_blocking_has_a_subject();

-- A legacy rating has no claim, so a cross-era dispute cannot be recorded through
-- role_conflict_claims. Its other side is a review id directly.
--
-- EVIDENCE ONLY, always. A legacy rating is never the subject of a blocking conflict:
-- it is already live, it has no claim for the predicate to reach, and the outcome that
-- affects it is supersession by moderation rather than a hold. So there is no role
-- column here, and nothing in this table can hold a rating.
CREATE TABLE role_conflict_reviews (
  conflict_id BIGINT NOT NULL REFERENCES role_conflicts(id) ON DELETE RESTRICT,
  review_id   UUID   NOT NULL REFERENCES reviews(id)        ON DELETE RESTRICT,
  added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (conflict_id, review_id)
);

-- THREE CAUSES, each naming its own provenance:
--   author_reclaimed     the author's anonymous publication replaced by their signed
--                        one; the superseded row is DELETED
--   moderator_superseded a cross-era duplicate resolved as the same job; the legacy
--                        row is SOFT-deleted and survives
--   duplicate_on_merge   a legacy uniqueness collision during a merge; the loser is
--                        KEPT, held, and marked merge_duplicate_of
--
-- An earlier version required draft_id NOT NULL, which only the first cause can
-- supply, so the moderated and merge cases could not be recorded at all.
CREATE TABLE review_supersessions (
  id            BIGSERIAL   PRIMARY KEY,
  cause         TEXT        NOT NULL,

  -- UUID, because reviews.id is UUID. No foreign key, because for the first cause the
  -- row it names is gone and a constraint would make this record unwritable.
  superseded_review_id UUID NOT NULL,
  -- Whether that row still exists. False for author_reclaimed, true for the other two.
  superseded_row_kept  BOOLEAN NOT NULL,
  superseded_published_at TIMESTAMPTZ,

  replaced_by   UUID        NOT NULL REFERENCES reviews(id) ON DELETE RESTRICT,

  -- Exactly one of these three, per cause.
  draft_id       BIGINT     REFERENCES captured_drafts(id)      ON DELETE RESTRICT,
  conflict_id    BIGINT     REFERENCES role_conflicts(id)       ON DELETE RESTRICT,
  merge_event_id BIGINT     REFERENCES manager_merge_events(id) ON DELETE RESTRICT,

  claim_id      BIGINT      REFERENCES role_claims(id) ON DELETE RESTRICT,
  at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  by            UUID        REFERENCES users(id),

  CONSTRAINT supersession_cause_known CHECK (cause IN
    ('author_reclaimed','moderator_superseded','duplicate_on_merge')),
  -- AN AUTHOR RECLAMATION RECORDS NO IDENTITY. Not a claim, not an actor.
  --
  -- This resolves a contradiction between two of this design's own promises: that the
  -- audit is append-only, and that a withdrawn rating is permanently severed from its
  -- author. An earlier revision required claim_id here and then said withdrawal would
  -- clear it, which the append-only trigger forbids.
  --
  -- The fix is NOT a permitted redaction. It is to never write the identity: the cause
  -- already says the actor was the author, so recording who adds nothing except the
  -- link that must not survive.
  CONSTRAINT supersession_author_reclaimed CHECK (cause <> 'author_reclaimed'
    OR (draft_id IS NOT NULL AND conflict_id IS NULL AND merge_event_id IS NULL
        AND superseded_row_kept = FALSE
        AND claim_id IS NULL AND by IS NULL)),
  CONSTRAINT supersession_moderated CHECK (cause <> 'moderator_superseded'
    OR (conflict_id IS NOT NULL AND draft_id IS NULL AND merge_event_id IS NULL
        AND superseded_row_kept = TRUE)),
  CONSTRAINT supersession_on_merge CHECK (cause <> 'duplicate_on_merge'
    OR (merge_event_id IS NOT NULL AND draft_id IS NULL AND conflict_id IS NULL
        AND superseded_row_kept = TRUE))
);

CREATE INDEX review_supersessions_superseded
  ON review_supersessions (superseded_review_id);
CREATE INDEX review_supersessions_replacement
  ON review_supersessions (replaced_by);

-- Append-only, same reasoning as claim_resolution_events. refuse_history_change() is
-- created in V101.
CREATE TRIGGER review_supersessions_append_only
  BEFORE UPDATE OR DELETE ON review_supersessions
  FOR EACH ROW EXECUTE FUNCTION refuse_history_change();

-- TWO FUNCTIONS, ONE INTERPRETATION OF THE ROLE RULES.
--
-- rating_role_is_publishable answers only "does this rating's employment permit
-- publication", with no reference to disposition or deleted_at.
-- rating_is_publishable adds the gate: a rating that is not live, or is deleted, is
-- trivially fine.
--
-- The split exists because the restoration sweep needs the first question WITHOUT the
-- gate. Asking rating_is_publishable about a row that is still deleted always answers
-- TRUE, by design, so the sweep's claimed-rating arm was vacuous: it could not
-- distinguish an accepted role from a rejected one and would set the rating live, to
-- fail at COMMIT.
CREATE OR REPLACE FUNCTION rating_role_is_publishable(p_review_id UUID)
RETURNS boolean AS $$
  SELECT COALESCE((
    SELECT CASE r.role_basis
             -- LEGACY: no role requirement at all, by design.
             WHEN 'legacy' THEN TRUE
             -- WITHDRAWN: no claim to go through, so the role it kept is checked
             -- directly. The exemption excuses a rating from needing a CLAIM; it never
             -- excuses it from needing an accepted ROLE.
             WHEN 'withdrawn' THEN COALESCE(wr.state = 'accepted', FALSE)
             WHEN 'claimed' THEN
                  COALESCE(c.resolution IN ('linked','created'), FALSE)
              AND COALESCE(ch.state = 'accepted', FALSE)
              AND NOT EXISTS (
                    SELECT 1 FROM role_conflict_claims rcc
                      JOIN role_conflicts cf ON cf.id = rcc.conflict_id
                     WHERE rcc.claim_id = c.id
                       -- SUBJECT only. Being cited as evidence is not being in doubt,
                       -- and treating it as doubt withdrew the one rating the merge
                       -- had deliberately kept live.
                       AND rcc.role = 'subject'
                       AND cf.blocks_publication
                       AND cf.resolved_at IS NULL)
           END
      FROM reviews r
      LEFT JOIN role_claims    c  ON c.id  = r.claim_id
      LEFT JOIN career_history ch ON ch.id = c.resolved_role_id
      -- A withdrawn rating's own route to its employment.
      LEFT JOIN career_history wr ON wr.id = r.withdrawn_role_id
     WHERE r.id = p_review_id
  ), TRUE);   -- no such review: nothing to enforce, and never NULL
$$ LANGUAGE sql STABLE;

-- THE one authoritative publication predicate.
--
-- IT IS TOTAL: always TRUE or FALSE, for any input, including an id that matches no
-- review. An early version was not, and the hole was in exactly the case the predicate
-- exists to catch: for a live rating whose claim was unresolved the first clause was
-- FALSE, the second NULL, FALSE OR NULL is NULL, and `IF NOT rating_is_publishable()`
-- skipped its RAISE because NOT NULL is not TRUE. A live rating with an unresolved
-- employment claim passed every publication check in the database.
--
-- Two independent fixes, deliberately both: COALESCE inside, so neither function can
-- return NULL, and IS NOT TRUE at every call site, so a future edit that reintroduces
-- a nullable branch still fails closed.
--
-- NOT keyed on role_required. It is FALSE for a withdrawn rating, so keying on it
-- short-circuited and a withdrawn rating could sit live against a REJECTED employment.
CREATE OR REPLACE FUNCTION rating_is_publishable(p_review_id UUID)
RETURNS boolean AS $$
  SELECT COALESCE((
    SELECT NOT (r.disposition = 'live' AND r.deleted_at IS NULL)
        OR rating_role_is_publishable(r.id)
      FROM reviews r WHERE r.id = p_review_id
  ), TRUE);
$$ LANGUAGE sql STABLE;

-- ONE function answers WHY, and every trigger quotes it. Each used to raise its own
-- hand-written reason, so whichever fired first described the failure in its own
-- terms: a rating held by a blocking conflict was reported as "its claim is
-- unresolved", which sends an operator to the wrong table. The conditions here are in
-- the same order as the predicate's, so the two cannot disagree about which one
-- failed.
CREATE OR REPLACE FUNCTION rating_publication_problem(p_review_id UUID)
RETURNS TEXT AS $$
  SELECT CASE
    WHEN r.id IS NULL THEN NULL
    WHEN NOT (r.disposition = 'live' AND r.deleted_at IS NULL) THEN NULL
    WHEN r.role_basis = 'legacy' THEN NULL
    WHEN r.role_basis = 'withdrawn' AND wr.id IS NULL
      THEN 'it was withdrawn against a role that no longer exists'
    WHEN r.role_basis = 'withdrawn' AND wr.state <> 'accepted'
      THEN 'it was withdrawn against a role that is ' || wr.state
    WHEN r.role_basis = 'withdrawn' THEN NULL
    WHEN r.claim_id IS NULL THEN 'claimed but has no claim'
    WHEN c.resolution IS NULL THEN 'its claim is unresolved'
    WHEN c.resolution NOT IN ('linked','created')
      THEN 'its claim is ' || c.resolution
    WHEN ch.id IS NULL THEN 'its claim resolves to no role'
    WHEN ch.state <> 'accepted' THEN 'its role is ' || ch.state
    WHEN EXISTS (SELECT 1 FROM role_conflict_claims rcc
                   JOIN role_conflicts cf ON cf.id = rcc.conflict_id
                  WHERE rcc.claim_id = c.id AND rcc.role = 'subject'
                    AND cf.blocks_publication AND cf.resolved_at IS NULL)
      THEN 'it is the subject of an open publication-blocking conflict'
    ELSE NULL END
    FROM reviews r
    LEFT JOIN role_claims    c  ON c.id  = r.claim_id
    LEFT JOIN career_history ch ON ch.id = c.resolved_role_id
    LEFT JOIN career_history wr ON wr.id = r.withdrawn_role_id
   WHERE r.id = p_review_id;
$$ LANGUAGE sql STABLE;

-- FIVE triggers, one predicate. Each scopes to the rows its own change can affect, so
-- none of them scans. An earlier version ran an unscoped EXISTS over every live
-- role-required review on every affected row, which is a full scan per row and would
-- degrade as ratings accumulate. Every one tests IS NOT TRUE rather than NOT.
--
-- UPDATE OF <columns> keeps them off every unrelated write: editing a rating's text or
-- a role's title fires nothing.

-- 1. On a rating: check only itself.
CREATE OR REPLACE FUNCTION assert_this_rating() RETURNS trigger AS $$
BEGIN
  IF rating_is_publishable(NEW.id) IS NOT TRUE THEN
    RAISE EXCEPTION 'review % may not be live: %', NEW.id,
                    COALESCE(rating_publication_problem(NEW.id),'unknown');
  END IF;
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER reviews_live_is_resolved
  -- withdrawn_role_id and role_basis are in this list because the whole publication
  -- question for a withdrawn rating hangs off them. Without them a LIVE withdrawn
  -- rating could be repointed from an accepted role to a rejected one and no check
  -- would fire: the initial withdrawal validated the role once, and a one-time
  -- validation is not an invariant.
  AFTER INSERT OR UPDATE OF disposition, claim_id, role_required, deleted_at,
                            withdrawn_role_id, role_basis
  ON reviews DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_this_rating();

-- 2. On a claim: check only the ratings that name THIS claim.
CREATE OR REPLACE FUNCTION assert_claims_ratings() RETURNS trigger AS $$
DECLARE bad UUID;
BEGIN
  SELECT r.id INTO bad FROM reviews r
   WHERE r.claim_id = NEW.id
     AND rating_is_publishable(r.id) IS NOT TRUE LIMIT 1;
  IF bad IS NOT NULL THEN
    RAISE EXCEPTION 'claim % leaves review % live: %', NEW.id, bad,
                    COALESCE(rating_publication_problem(bad),'unknown');
  END IF;
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER claims_live_is_resolved
  AFTER UPDATE OF resolution, resolved_role_id ON role_claims
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_claims_ratings();

-- 3. On a role: check the ratings that reach it by EITHER route.
CREATE OR REPLACE FUNCTION assert_roles_ratings() RETURNS trigger AS $$
DECLARE bad UUID;
BEGIN
  -- TWO ROUTES from a role to its ratings, not one. Nulling claim_id on withdrawal
  -- also removed the route this trigger used to find the rating, so a role rejected
  -- while a withdrawn rating pointed at it was never checked and the rating stayed
  -- live against a rejected employment.
  SELECT r.id INTO bad FROM reviews r
   WHERE (r.claim_id IN (SELECT c.id FROM role_claims c
                          WHERE c.resolved_role_id = NEW.id)
          OR r.withdrawn_role_id = NEW.id)
     AND rating_is_publishable(r.id) IS NOT TRUE LIMIT 1;
  IF bad IS NOT NULL THEN
    RAISE EXCEPTION 'role % leaves review % live: %', NEW.id, bad,
                    COALESCE(rating_publication_problem(bad),'unknown');
  END IF;
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER roles_live_is_resolved
  AFTER UPDATE OF state ON career_history
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_roles_ratings();

-- 4 and 5. On a conflict, and on a claim's membership of one: check the ratings under
-- it. This is the direction that was missing entirely. It covers raising a blocking
-- conflict, REOPENING a resolved one (resolved_at back to null, which no other trigger
-- sees), and linking a further claim as evidence.
CREATE OR REPLACE FUNCTION assert_conflicts_ratings() RETURNS trigger AS $$
DECLARE bad UUID; v_conflict BIGINT;
BEGIN
  -- B2 FINDING E2: IF/ELSE, not a CASE expression. plpgsql resolves BOTH operands of a
  -- CASE against the record, and NEW has no conflict_id when this fires on
  -- role_conflicts, so every conflict insert raised "record new has no field
  -- conflict_id". An IF branch that is not taken is never planned, so this form works.
  -- Found by executing it.
  IF TG_TABLE_NAME = 'role_conflicts' THEN
    v_conflict := NEW.id;
  ELSE
    v_conflict := NEW.conflict_id;
  END IF;
  SELECT r.id INTO bad
    FROM role_conflict_claims rcc
    JOIN reviews r ON r.claim_id = rcc.claim_id
   WHERE rcc.conflict_id = v_conflict
     AND rcc.role = 'subject'
     AND rating_is_publishable(r.id) IS NOT TRUE LIMIT 1;
  IF bad IS NOT NULL THEN
    RAISE EXCEPTION 'conflict % leaves review % live: %', v_conflict, bad,
                    COALESCE(rating_publication_problem(bad),'unknown');
  END IF;
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER conflicts_live_is_resolved
  AFTER INSERT OR UPDATE OF resolved_at, resolution, kind ON role_conflicts
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_conflicts_ratings();

CREATE CONSTRAINT TRIGGER conflict_claims_live_is_resolved
  AFTER INSERT OR UPDATE ON role_conflict_claims
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_conflicts_ratings();

-- THE SWEEP GUARDS. Defence in depth over an unconditional invariant, NOT a permission
-- boundary: a current_setting guard catches a bug in the sweep, which is the realistic
-- failure, and catches nothing at all from a writer that does not set the label. Both
-- applications share one database user, so there is no privilege separation to lean on
-- without a second role and a second connection pool. What is genuinely enforced,
-- regardless of who is writing, is the invariant itself, by the five triggers above.
CREATE OR REPLACE FUNCTION refuse_sweep_resolution() RETURNS trigger AS $$
BEGIN
  IF current_setting('rmm.actor', true) = 'sweep' THEN
    RAISE EXCEPTION 'the enrichment sweep may not resolve conflicts';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER conflicts_sweep_cannot_resolve
  BEFORE UPDATE OF resolution, resolved_at, resolved_by, note ON role_conflicts
  FOR EACH ROW EXECUTE FUNCTION refuse_sweep_resolution();

-- NEW.kind, NOT NEW.blocks_publication.
--
-- blocks_publication is a STORED generated column, and PostgreSQL computes stored
-- generated columns AFTER BEFORE triggers have run. Reading NEW's generated value in a
-- BEFORE trigger is unsupported: it is not the value the row will end up with. An
-- earlier revision of this guard did exactly that, so the check would not have done
-- what it says. The guard therefore tests the input the generation expression is built
-- from, through the SAME function the generated column uses.
CREATE OR REPLACE FUNCTION refuse_sweep_blocking() RETURNS trigger AS $$
BEGIN
  IF current_setting('rmm.actor', true) = 'sweep'
     AND kind_blocks_publication(NEW.kind) THEN
    RAISE EXCEPTION 'the enrichment sweep may not raise a blocking conflict '
                    '(kind %)', NEW.kind;
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

-- ONE definition of this trigger. An earlier revision declared it twice, for
-- INSERT OR UPDATE OF kind and then for INSERT alone; the second CREATE would have
-- failed on the duplicate name and taken this migration with it.
CREATE TRIGGER conflicts_sweep_is_enrichment_only
  BEFORE INSERT OR UPDATE OF kind ON role_conflicts
  FOR EACH ROW EXECUTE FUNCTION refuse_sweep_blocking();

-- OBSERVABILITY for the enrichment sweep.
CREATE TABLE conflict_sweep_runs (
  id            BIGSERIAL   PRIMARY KEY,
  started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at   TIMESTAMPTZ,
  outcome       TEXT        NOT NULL DEFAULT 'ok',

  -- THE CURSOR IS A TUPLE, and that is the correction that matters. Persisting the
  -- timestamp alone cannot resume precisely: thousands of rows can share one
  -- updated_at, and a restart would either re-read the whole timestamp or skip the
  -- remainder of it depending on whether the resume predicate used > or >=. Storing
  -- both makes the predicate exact:
  --
  --   WHERE (updated_at, id) > ($through_at, $through_id)
  --
  -- A strict tuple comparison, so no row is seen twice and none is skipped.
  through_at    TIMESTAMPTZ,
  through_id    BIGINT,
  CONSTRAINT conflict_sweep_cursor_is_whole
    CHECK ((through_at IS NULL) = (through_id IS NULL)),

  batches        INT        NOT NULL DEFAULT 0,
  opened         INT        NOT NULL DEFAULT 0,
  links_added    INT        NOT NULL DEFAULT 0,
  stale_evidence INT        NOT NULL DEFAULT 0,
  suppressed     INT        NOT NULL DEFAULT 0,   -- held back by a prior decision
  error_detail   TEXT,
  source         TEXT,
  CONSTRAINT conflict_sweep_runs_outcome
    CHECK (outcome IN ('ok','skipped','failed'))
);

CREATE INDEX conflict_sweep_runs_finished
  ON conflict_sweep_runs (finished_at DESC);

-- Resuming reads the cursor from the most recent run that HAS one, not the most recent
-- run: a 'skipped' or 'failed' run leaves it null and must not reset the sweep to the
-- epoch.
CREATE OR REPLACE FUNCTION conflict_sweep_cursor()
RETURNS TABLE (through_at TIMESTAMPTZ, through_id BIGINT) AS $$
  SELECT through_at, through_id FROM conflict_sweep_runs
   WHERE through_at IS NOT NULL
   ORDER BY through_at DESC, through_id DESC
   LIMIT 1;
$$ LANGUAGE sql STABLE;

-- NULL when no sweep has ever completed, and the caller MUST treat that as stale. The
-- company-stats version of this function coalesced to -infinity, raised on the
-- subtraction, and the caller's fallback reported a database where maintenance had
-- NEVER run as "0h ago". Counts only 'ok'.
CREATE OR REPLACE FUNCTION conflict_sweep_age() RETURNS INTERVAL AS $$
  SELECT CASE WHEN MAX(finished_at) IS NULL THEN NULL
              ELSE now() - MAX(finished_at) END
    FROM conflict_sweep_runs WHERE outcome = 'ok';
$$ LANGUAGE sql STABLE;


-- ==========================================================================
-- ORIGINALLY V104__rating_opportunities.sql
-- ==========================================================================

-- PHASE 4, migration 1 of 1: the rating allowance, as opportunities rather than a counter.
--
-- Rev 268, "The rating allowance", "Merge accounting: count opportunities, never sum
-- counters", "PRIVACY CORRECTION: two identity-reconstruction paths", "The opportunity
-- merge, as an executable sequence", and the "AS EXECUTED" section, which wins wherever
-- it differs from the earlier text.
--
-- Two opportunities per contributor per subject, counted independently by type and
-- subject. An opportunity is consumed when one is OPENED, never when a rating is
-- deleted, which is what makes an edit free and a replacement inside the three-day
-- window free. The cap is on opportunities and never on published ratings: retiring an
-- opportunity does not retire a rating, and after a merge that retires over-cap rows
-- every historical rating remains live and publicly counted.
--
-- INERT UNTIL THE FLAG. Nothing writes either table until role-aware submission is
-- enabled (ROLE_AWARE_SUBMISSION_ENABLED). Creating them earlier is deliberate: the
-- charge has to be in the same transaction as the rating insert, so the table must
-- exist before that transaction is written.
--
-- WHY THIS IS V104 AND ENFORCEMENT IS NOT. Rev 268 allocated V104 to the S4 post-cutover
-- enforcement (role_required default, pre_cutover_drafts, review_exemption_is_genuine).
-- That enforcement is the point of no return and belongs to phase 6; these tables are
-- needed four phases earlier, so they take V104 and enforcement moves to V105. Flyway
-- applies in version order, so enforcement simply arrives later with a higher number.

CREATE TABLE rating_opportunities (
  id           BIGSERIAL PRIMARY KEY,

  -- CASCADE. An allowance is a limit on an account; no account, no limit to enforce.
  -- The honest consequence, which no schema fixes: the lifetime allowance is only as
  -- durable as the account, so deleting and re-registering resets it. Closing that
  -- needs an email- or device-level limit, which is not designed here.
  user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- Polymorphic by design and deliberately carrying no foreign key: subject_id names
  -- one of three tables depending on subject_type, and a polymorphic reference cannot
  -- be one. The CHECK plus the service's own resolution is the enforcement. The cost is
  -- that a deleted subject leaves an orphan row, which is harmless, and is also the
  -- behaviour that stops a deletion handing somebody a fresh allowance.
  subject_type TEXT        NOT NULL,
  subject_id   BIGINT      NOT NULL,

  ordinal      SMALLINT    NOT NULL,
  opened_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- Informational, and NOT an identity path: a claim already carries user_id, and a
  -- restored rating no longer carries claim_id, so this reveals nothing a restored
  -- rating could be joined to. It is emphatically NOT the opportunity's identity, which
  -- an earlier revision tried and which breaks on the second opportunity: the claim
  -- identifies the EMPLOYMENT, which is deliberately singular, while the opportunities
  -- on it are deliberately plural.
  origin_claim_id BIGINT   REFERENCES role_claims(id),

  -- NO COLUMN IN THIS TABLE MAY EVER HOLD A RATING ID. Stated as a rule rather than as
  -- the outcome of a review, because two separate revisions violated it. A value equal
  -- to a review id, sitting in a row that also carries user_id, recovers the author of
  -- an anonymised rating in one query and needs no foreign key to do it:
  --     SELECT user_id FROM rating_opportunities WHERE opened_by_review_id = :restored;
  -- "The reference points the other way" is not a defence. A join has no direction.

  CONSTRAINT opportunity_subject_known CHECK (subject_type IN
    ('manager','company','interview')),
  CONSTRAINT opportunity_ordinal_bounded CHECK (ordinal IN (1,2)),

  -- DEFERRABLE because the merge's move-and-renumber is checked DURING the move, not
  -- after it: a contributor holding ordinal 1 on both subjects collides the moment the
  -- first row moves. A PostgreSQL fact decides the shape here, and it rules out the
  -- obvious alternative of keeping over-cap rows behind a partial index on retired_at:
  --     a UNIQUE CONSTRAINT can be DEFERRABLE,  cannot be partial
  --     a UNIQUE INDEX      can be partial,     cannot be deferred
  -- So: a deferrable constraint, deferred by the merge exactly as it already defers
  -- three composite foreign keys, with over-cap rows moved to an append-only audit
  -- table rather than retired in place.
  --
  -- INITIALLY IMMEDIATE, so every ordinary write is checked at once and only the merge
  -- pays the deferral.
  CONSTRAINT opportunity_one_per_ordinal
    UNIQUE (user_id, subject_type, subject_id, ordinal)
    DEFERRABLE INITIALLY IMMEDIATE
);

-- DEVIATION FROM REV 268, with its reason. The design specifies no index here beyond the
-- unique constraint. That constraint's index is leading on user_id, so it serves the
-- authorization lookup (user_id, subject_type, subject_id) as a prefix but cannot serve
-- the merge, which reads WHERE subject_type = 'manager' AND subject_id IN (survivor,
-- absorbed) with no user_id. Without this the merge sequentially scans a table that
-- grows with every rating ever submitted.
CREATE INDEX rating_opportunities_subject
  ON rating_opportunities (subject_type, subject_id);

-- Over-cap opportunities are not deleted, they are recorded. A contributor who genuinely
-- exercised three opportunities across what turned out to be one subject is capped at
-- two, and the third remains visible here rather than vanishing.
CREATE TABLE rating_opportunity_retirements (
  id             BIGSERIAL   PRIMARY KEY,

  -- NULLABLE, AND NO FOREIGN KEY AT ALL, following review_supersessions: "the row it
  -- names is gone and a constraint would make this record unwritable". This also
  -- resolves a real CASCADE-versus-append-only conflict, because the trigger below
  -- refuses DELETE, so a cascade would have failed. Account deletion clears this column
  -- explicitly instead; erasure was incomplete exactly once, here, because dropping the
  -- foreign key stopped the cascade blocking but did not clear the value.
  user_id        UUID,

  subject_type   TEXT        NOT NULL,
  former_subject_id    BIGINT NOT NULL,
  surviving_subject_id BIGINT NOT NULL,
  former_ordinal SMALLINT    NOT NULL,
  opened_at      TIMESTAMPTZ NOT NULL,

  -- Merge provenance is two different types, so it is two columns, following the pattern
  -- review_supersessions already uses. manager_merge_events.id is BIGINT and
  -- company_merges.id is UUID: one column could not have held both whatever the foreign
  -- key said.
  manager_merge_event_id BIGINT REFERENCES manager_merge_events(id),
  company_merge_id       UUID   REFERENCES company_merges(id),

  retired_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  reason         TEXT        NOT NULL DEFAULT 'over_cap_on_merge',

  CONSTRAINT retirement_subject_known CHECK (subject_type IN
    ('manager','company','interview')),

  -- Exactly one, and it must match what was merged. An interview subject is reconciled
  -- BY its company's merge, so it carries the company id and does not invent an event of
  -- its own.
  CONSTRAINT retirement_provenance_matches_subject CHECK (
    CASE subject_type
      WHEN 'manager' THEN manager_merge_event_id IS NOT NULL
                      AND company_merge_id IS NULL
      ELSE                company_merge_id IS NOT NULL
                      AND manager_merge_event_id IS NULL
    END)
);

-- Append-only, with ONE permitted exception. THE RULE, which execution forced into this
-- shape: an identity may be ADDED once (adoption) and REMOVED once (erasure), and may
-- NEVER be transferred. Adding is how a contributor adopts an anonymous claim; removing
-- is what deleting an account means; transferring is the only one that is always an
-- attack.
--
-- This is refuse_retirement_change rather than V101's refuse_history_change because
-- ON DELETE SET NULL performs an UPDATE, which fires user-defined triggers: a flat
-- append-only trigger made account deletion impossible. The permitted shape is user_id
-- cleared with every other column byte-identical. DELETE is refused always.
CREATE OR REPLACE FUNCTION refuse_retirement_change() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'this table is append-only';
  END IF;
  IF NEW.user_id IS NULL AND OLD.user_id IS NOT NULL
     AND ROW(NEW.id, NEW.subject_type, NEW.former_subject_id,
             NEW.surviving_subject_id, NEW.former_ordinal, NEW.opened_at,
             NEW.manager_merge_event_id, NEW.company_merge_id,
             NEW.retired_at, NEW.reason)
       IS NOT DISTINCT FROM
         ROW(OLD.id, OLD.subject_type, OLD.former_subject_id,
             OLD.surviving_subject_id, OLD.former_ordinal, OLD.opened_at,
             OLD.manager_merge_event_id, OLD.company_merge_id,
             OLD.retired_at, OLD.reason) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'this table is append-only, except clearing user_id';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER rating_opportunity_retirements_append_only
  BEFORE UPDATE OR DELETE ON rating_opportunity_retirements
  FOR EACH ROW EXECUTE FUNCTION refuse_retirement_change();

-- DELIBERATELY NOT CREATED: rev 268 sanctions "an aggregate-only view" as the operational
-- interface for capacity questions. This repository does not use views as application
-- architecture (CLAUDE.md section 21), and that invariant takes precedence over the
-- design. The aggregate read belongs in explicit repository SQL, which is also the only
-- form that stays greppable. The access rules the design attaches to it still hold:
-- rating_opportunities is on the denylist for every export and analytics feed, no
-- administrative screen renders a contributor-scoped row, and the only legitimate reads
-- are the service's own one-subject authorization lookup and aggregates carrying no
-- user_id.


-- ==========================================================================
-- ORIGINALLY V105__identity_erasure.sql
-- ==========================================================================

-- PHASE 4, migration 2: identity erasure, so that deleting an account is possible AND complete.
--
-- Rev 268, "The erasure rule, and the two triggers execution forced it into", plus the cascade list
-- in "Integration corrections".
--
-- THE PRINCIPLE: an account's deletion must not destroy evidence, and must not be blocked by it.
--
-- THE RULE: an identity may be ADDED once (adoption) and REMOVED once (erasure). It may NEVER be
-- transferred. Adding is how a contributor adopts an anonymous claim; removing is what deleting an
-- account means; transferring is the only one of the three that is always an attack.
--
-- WHY THIS IS NEEDED. V100 to V103 wrote `REFERENCES users(id)` with no ON DELETE action, which
-- PostgreSQL reads as NO ACTION, where rev 268 prescribes SET NULL. One signed-in contributor's
-- claim therefore made DELETE FROM users impossible. Worse, the deletion workflow anonymised first
-- and deleted last without a transaction, so a refused delete left the person HALF-DELETED: their
-- ratings severed and anonymous, their account still present, and no way to put the identity back.
-- Verified through the real workflow, not inferred from the catalogue.
--
-- AUDITED FROM pg_constraint, not from a list somebody wrote down: 32 foreign keys reference
-- users(id). 12 were NO ACTION, 13 CASCADE, 7 already SET NULL. Every one of the 32 was reviewed,
-- and the decisions for the ones left alone are recorded at the end of this file so that "we did
-- not think about it" and "we thought about it and left it" are distinguishable later.

-- ===========================================================================
-- 1. THE AUTHORIZATION CONDITION FOR AN ERASURE
-- ===========================================================================
-- Clearing an actor column is permitted ONLY because the account is gone. That is a different
-- question from whether the column may legally hold NULL, and conflating the two opens a hole:
-- dropping NOT NULL below makes `UPDATE manager_merge_events SET merged_by = NULL` syntactically
-- possible, and a trigger that merely stamps "withdrawn" on any such update would help an attacker
-- erase a moderator from the audit trail of their own decisions.
--
-- So every erasure transition in this migration carries this guard. It works because of a fact
-- about PostgreSQL that was measured rather than assumed: when ON DELETE SET NULL fires its UPDATE,
-- the parent row is ALREADY GONE, so the condition is true for a real deletion and false for a
-- direct tamper.
--
--     real cascade from DELETE FROM users  ->  users row absent  ->  permitted
--     direct UPDATE while the account lives ->  users row present ->  REFUSED
CREATE OR REPLACE FUNCTION actor_is_erased(p_actor UUID) RETURNS boolean AS $$
  SELECT p_actor IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users WHERE id = p_actor);
$$ LANGUAGE sql STABLE;

-- ===========================================================================
-- 2. APPEND-ONLY HISTORY: permit the erasure, and nothing else
-- ===========================================================================
-- ON DELETE SET NULL performs an UPDATE, which fires user-defined triggers, so changing the
-- foreign-key action alone does not make deletion work. Both of these tables ran
-- refuse_history_change(), whose entire body is a RAISE, so the cascade was refused outright.
--
-- Rev 268 supplies refuse_history_change_except_erasure() for this, but it CANNOT be reused here:
-- it is written for claim_testimony's columns and keys on user_id, while these two tables have
-- different columns and erase `by`. Two functions rather than one, because a ROW() comparison has
-- to name every column it is holding constant.
--
-- Each permits EXACTLY one shape: the actor cleared, the account already gone, and every other
-- column byte-identical. DELETE is refused always.

CREATE OR REPLACE FUNCTION refuse_event_change_except_erasure() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'this table is append-only';
  END IF;
  IF NEW."by" IS NULL AND OLD."by" IS NOT NULL AND actor_is_erased(OLD."by")
     AND ROW(NEW.id, NEW.claim_id, NEW.event, NEW.from_role_id, NEW.to_role_id,
             NEW.merge_event_id, NEW.resolution, NEW.reason, NEW.at)
       IS NOT DISTINCT FROM
         ROW(OLD.id, OLD.claim_id, OLD.event, OLD.from_role_id, OLD.to_role_id,
             OLD.merge_event_id, OLD.resolution, OLD.reason, OLD.at) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'this table is append-only, except clearing the actor of a deleted account';
END; $$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION refuse_supersession_change_except_erasure() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'this table is append-only';
  END IF;
  IF NEW."by" IS NULL AND OLD."by" IS NOT NULL AND actor_is_erased(OLD."by")
     AND ROW(NEW.id, NEW.cause, NEW.superseded_review_id, NEW.superseded_row_kept,
             NEW.superseded_published_at, NEW.replaced_by, NEW.draft_id,
             NEW.conflict_id, NEW.merge_event_id, NEW.claim_id, NEW.at)
       IS NOT DISTINCT FROM
         ROW(OLD.id, OLD.cause, OLD.superseded_review_id, OLD.superseded_row_kept,
             OLD.superseded_published_at, OLD.replaced_by, OLD.draft_id,
             OLD.conflict_id, OLD.merge_event_id, OLD.claim_id, OLD.at) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'this table is append-only, except clearing the actor of a deleted account';
END; $$ LANGUAGE plpgsql;

DROP TRIGGER claim_resolution_events_append_only ON claim_resolution_events;
CREATE TRIGGER claim_resolution_events_append_only
  BEFORE UPDATE OR DELETE ON claim_resolution_events
  FOR EACH ROW EXECUTE FUNCTION refuse_event_change_except_erasure();

DROP TRIGGER review_supersessions_append_only ON review_supersessions;
CREATE TRIGGER review_supersessions_append_only
  BEFORE UPDATE OR DELETE ON review_supersessions
  FOR EACH ROW EXECUTE FUNCTION refuse_supersession_change_except_erasure();

-- V104 wrote this one without the authorization guard, because the guard had not been thought of
-- yet. Replaced here rather than by editing V104, whose checksum is already recorded.
--
-- rating_opportunity_retirements has NO foreign key to users, deliberately, so nothing cascades and
-- the deletion clears it explicitly. The clear therefore has to happen AFTER the account row is
-- gone, which is the order UserRepository.deleteWithReviewAnonymization now uses.
CREATE OR REPLACE FUNCTION refuse_retirement_change() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'this table is append-only';
  END IF;
  IF NEW.user_id IS NULL AND OLD.user_id IS NOT NULL AND actor_is_erased(OLD.user_id)
     AND ROW(NEW.id, NEW.subject_type, NEW.former_subject_id,
             NEW.surviving_subject_id, NEW.former_ordinal, NEW.opened_at,
             NEW.manager_merge_event_id, NEW.company_merge_id,
             NEW.retired_at, NEW.reason)
       IS NOT DISTINCT FROM
         ROW(OLD.id, OLD.subject_type, OLD.former_subject_id,
             OLD.surviving_subject_id, OLD.former_ordinal, OLD.opened_at,
             OLD.manager_merge_event_id, OLD.company_merge_id,
             OLD.retired_at, OLD.reason) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'this table is append-only, except clearing the user of a deleted account';
END; $$ LANGUAGE plpgsql;

-- ===========================================================================
-- 3. THE ACTOR-WITHDRAWN MARKER
-- ===========================================================================
-- Four columns cannot simply take SET NULL. Three are NOT NULL, so a SET NULL would be accepted by
-- ALTER TABLE and then fail at the moment somebody is deleted. The fourth is paired with a
-- timestamp by a CHECK.
--
-- Rev 268: "administrator columns -> SET NULL, with the actor_withdrawn convention already in the
-- design so a record does not read as 'automatic'". Without the marker, a nulled merged_by is
-- indistinguishable from a merge no human performed, which is a worse record than none.
--
-- FALSE and not NULL, so the flag is never a third state. Every existing row has a named actor.

ALTER TABLE claim_corrections
  ALTER COLUMN corrected_by DROP NOT NULL,
  ADD COLUMN actor_withdrawn BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE manager_merge_events
  ALTER COLUMN merged_by DROP NOT NULL,
  ADD COLUMN actor_withdrawn BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE company_merges
  ALTER COLUMN merged_by DROP NOT NULL,
  ADD COLUMN actor_withdrawn BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE role_claims
  ADD COLUMN canonicalization_actor_withdrawn BOOLEAN NOT NULL DEFAULT FALSE;

-- An actor may be absent ONLY because they were erased. This is what makes dropping NOT NULL safe:
-- an INSERT with no actor is still refused, so "nobody did this" never becomes representable.
ALTER TABLE claim_corrections
  ADD CONSTRAINT correction_actor_absent_is_withdrawn
    CHECK ((corrected_by IS NULL) = actor_withdrawn);

ALTER TABLE manager_merge_events
  ADD CONSTRAINT merge_actor_absent_is_withdrawn
    CHECK ((merged_by IS NULL) = actor_withdrawn);

ALTER TABLE company_merges
  ADD CONSTRAINT company_merge_actor_absent_is_withdrawn
    CHECK ((merged_by IS NULL) = actor_withdrawn);

-- The canonicalization CHECK paired the actor WITH the timestamp, so nulling the actor violated it.
-- Relaxed so WHEN it happened and FROM WHICH ROLE still pair with each other, while the actor may
-- be absent exactly when it was withdrawn. The record still says a canonicalization happened, when,
-- and from where; only who did it is gone.
ALTER TABLE role_claims
  DROP CONSTRAINT claim_canonicalization_is_complete;
ALTER TABLE role_claims
  ADD CONSTRAINT claim_canonicalization_is_complete CHECK (
        ((canonicalized_at IS NULL) = (canonicalized_from IS NULL))
    AND (canonicalized_at IS NOT NULL
         OR (canonicalized_by IS NULL AND NOT canonicalization_actor_withdrawn))
    AND (canonicalized_at IS NULL
         OR (canonicalized_by IS NULL) = canonicalization_actor_withdrawn));

-- AND THE TRIGGERS THAT MAKE THOSE CHECKS SATISFIABLE.
--
-- A foreign key's SET NULL nulls its own column and NOTHING else. It cannot maintain a companion
-- flag, so the cascade violates the very CHECK meant to make the flag trustworthy:
--
--     ERROR:  new row for relation "claim_corrections" violates check constraint
--             "correction_actor_absent_is_withdrawn"
--     CONTEXT: SQL statement "UPDATE ONLY "public"."claim_corrections"
--              SET "corrected_by" = NULL WHERE $1 = "corrected_by""
--
-- Found by executing this migration against a real deletion rather than by reading it. The fix uses
-- the same mechanism that caused the original problem: a SET NULL performs an UPDATE, so a scoped
-- BEFORE UPDATE trigger sees it and sets the marker in the same statement, atomically.
--
-- EACH ONE REFUSES A TAMPER. Setting the actor to NULL while the account still exists raises,
-- rather than being quietly stamped as a withdrawal. Scoped UPDATE OF <actor>, so no other write to
-- these tables fires them.

CREATE OR REPLACE FUNCTION mark_correction_actor_withdrawn() RETURNS trigger AS $$
BEGIN
  IF NEW.corrected_by IS NULL AND OLD.corrected_by IS NOT NULL THEN
    IF NOT actor_is_erased(OLD.corrected_by) THEN
      RAISE EXCEPTION 'an actor may only be cleared when their account has been deleted';
    END IF;
    NEW.actor_withdrawn := TRUE;
  ELSIF NEW.corrected_by IS DISTINCT FROM OLD.corrected_by THEN
    RAISE EXCEPTION 'an actor may be cleared but never reassigned';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER correction_actor_withdrawal
  BEFORE UPDATE OF corrected_by ON claim_corrections
  FOR EACH ROW EXECUTE FUNCTION mark_correction_actor_withdrawn();

CREATE OR REPLACE FUNCTION mark_merge_actor_withdrawn() RETURNS trigger AS $$
BEGIN
  IF NEW.merged_by IS NULL AND OLD.merged_by IS NOT NULL THEN
    IF NOT actor_is_erased(OLD.merged_by) THEN
      RAISE EXCEPTION 'an actor may only be cleared when their account has been deleted';
    END IF;
    NEW.actor_withdrawn := TRUE;
  ELSIF NEW.merged_by IS DISTINCT FROM OLD.merged_by THEN
    RAISE EXCEPTION 'an actor may be cleared but never reassigned';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER merge_event_actor_withdrawal
  BEFORE UPDATE OF merged_by ON manager_merge_events
  FOR EACH ROW EXECUTE FUNCTION mark_merge_actor_withdrawn();

CREATE TRIGGER company_merge_actor_withdrawal
  BEFORE UPDATE OF merged_by ON company_merges
  FOR EACH ROW EXECUTE FUNCTION mark_merge_actor_withdrawn();

CREATE OR REPLACE FUNCTION mark_canonicalization_actor_withdrawn() RETURNS trigger AS $$
BEGIN
  IF NEW.canonicalized_by IS NULL AND OLD.canonicalized_by IS NOT NULL THEN
    IF NOT actor_is_erased(OLD.canonicalized_by) THEN
      RAISE EXCEPTION 'an actor may only be cleared when their account has been deleted';
    END IF;
    NEW.canonicalization_actor_withdrawn := TRUE;
  ELSIF NEW.canonicalized_by IS DISTINCT FROM OLD.canonicalized_by
        AND OLD.canonicalized_by IS NOT NULL THEN
    RAISE EXCEPTION 'an actor may be cleared but never reassigned';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER claim_canonicalization_actor_withdrawal
  BEFORE UPDATE OF canonicalized_by ON role_claims
  FOR EACH ROW EXECUTE FUNCTION mark_canonicalization_actor_withdrawn();

-- ===========================================================================
-- 4. THE TWELVE NO ACTION FOREIGN KEYS
-- ===========================================================================
-- Every one only WIDENS what is permitted: a delete that used to be refused now nulls a column. No
-- existing row can violate any of them, no INSERT or UPDATE path changes, and RMM needs no lockstep
-- change.

-- The contributor's own claim. SET NULL and never CASCADE: cascading would delete the claim, and
-- with it the employment record and the publication status of a rating that is staying live. The
-- claim's PURPOSE is to stop one contributor rating one employment twice, and a deleted account
-- cannot rate anything, so the identity is no longer needed. The claim survives as the record.
ALTER TABLE role_claims DROP CONSTRAINT role_claims_user_id_fkey;
ALTER TABLE role_claims ADD CONSTRAINT role_claims_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE role_claims DROP CONSTRAINT role_claims_resolved_by_fkey;
ALTER TABLE role_claims ADD CONSTRAINT role_claims_resolved_by_fkey
  FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE role_claims DROP CONSTRAINT role_claims_canonicalized_by_fkey;
ALTER TABLE role_claims ADD CONSTRAINT role_claims_canonicalized_by_fkey
  FOREIGN KEY (canonicalized_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE claim_corrections DROP CONSTRAINT claim_corrections_corrected_by_fkey;
ALTER TABLE claim_corrections ADD CONSTRAINT claim_corrections_corrected_by_fkey
  FOREIGN KEY (corrected_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE claim_resolution_events DROP CONSTRAINT claim_resolution_events_by_fkey;
ALTER TABLE claim_resolution_events ADD CONSTRAINT claim_resolution_events_by_fkey
  FOREIGN KEY ("by") REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE role_conflicts DROP CONSTRAINT role_conflicts_resolved_by_fkey;
ALTER TABLE role_conflicts ADD CONSTRAINT role_conflicts_resolved_by_fkey
  FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE review_supersessions DROP CONSTRAINT review_supersessions_by_fkey;
ALTER TABLE review_supersessions ADD CONSTRAINT review_supersessions_by_fkey
  FOREIGN KEY ("by") REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE manager_merge_events DROP CONSTRAINT manager_merge_events_merged_by_fkey;
ALTER TABLE manager_merge_events ADD CONSTRAINT manager_merge_events_merged_by_fkey
  FOREIGN KEY (merged_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE company_merges DROP CONSTRAINT company_merges_merged_by_fkey;
ALTER TABLE company_merges ADD CONSTRAINT company_merges_merged_by_fkey
  FOREIGN KEY (merged_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE captured_drafts DROP CONSTRAINT captured_drafts_resolved_by_fkey;
ALTER TABLE captured_drafts ADD CONSTRAINT captured_drafts_resolved_by_fkey
  FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE reviews DROP CONSTRAINT reviews_deleted_by_fkey;
ALTER TABLE reviews ADD CONSTRAINT reviews_deleted_by_fkey
  FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE interview_reviews DROP CONSTRAINT interview_reviews_deleted_by_fkey;
ALTER TABLE interview_reviews ADD CONSTRAINT interview_reviews_deleted_by_fkey
  FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL;

-- ===========================================================================
-- 5. THE THREE CONTRIBUTION TYPES: CASCADE -> SET NULL
-- ===========================================================================
-- A person's contributions must survive their account. These three were CASCADE, which means
-- DELETE FROM users DESTROYS them, and only one of the three was protected: the application nulled
-- reviews.user_id first, so the cascade found nothing to delete. company_reviews and
-- interview_reviews had no such protection, so deleting an account silently destroyed every company
-- rating and interview experience that person had written while their manager ratings survived.
--
-- Two independent problems with leaving that to application code:
--   * it is one method's responsibility to prevent data loss, and any other path that deletes a
--     user destroys contributions without anybody noticing;
--   * company and interview STATISTICS fall, because both tables carry statement-level AFTER DELETE
--     triggers that recalculate company_stats_live downward. A person deleting their account
--     silently reduced the published figures for every company they had rated.
--
-- SET NULL puts the protection in the schema. The application still severs the byline, but
-- destruction is no longer reachable, by any path.
--
-- VERIFIED SAFE AGAINST UNIQUENESS, which is the thing that could have made this fail:
--   company_reviews_one_per_user_company          (user_id, company_id)                WHERE deleted_at IS NULL
--   interview_reviews_one_per_user_company_year   (user_id, company_id, interview_year) WHERE deleted_at IS NULL
--   idx_reviews_user_manager_role_legacy          partial on user_id IS NOT NULL
-- None is NULLS NOT DISTINCT, so NULLs are distinct and any number of anonymised rows coexist. The
-- reviews index is partial on user_id IS NOT NULL, so an anonymised row leaves it entirely.
ALTER TABLE reviews DROP CONSTRAINT reviews_user_id_fkey;
ALTER TABLE reviews ADD CONSTRAINT reviews_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE company_reviews DROP CONSTRAINT company_reviews_user_id_fkey;
ALTER TABLE company_reviews ADD CONSTRAINT company_reviews_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE interview_reviews DROP CONSTRAINT interview_reviews_user_id_fkey;
ALTER TABLE interview_reviews ADD CONSTRAINT interview_reviews_user_id_fkey
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

-- ===========================================================================
-- 6. THE TEN CASCADES DELIBERATELY LEFT ALONE
-- ===========================================================================
-- Reviewed rather than assumed, because "every existing cascade is fine" is exactly the assumption
-- that produced the three above. Recorded so a later reader can tell a decision from an oversight.
--
--   rating_opportunities        KEEP CASCADE. Rev 268 specifies it: an allowance is a limit on an
--                               account, and with no account there is no limit to enforce. The
--                               honest consequence, which no schema fixes, is that deleting and
--                               re-registering resets the two-per-subject cap.
--   user_resumes                KEEP CASCADE. The person's own document. Deleting an account must
--                               destroy it; retaining it anonymously would be the defect.
--   notifications               KEEP CASCADE. Addressed to an account that no longer exists.
--   verification_sends          KEEP CASCADE. Transient delivery records.
--   manager_proof_challenges    KEEP CASCADE. Short-lived proof-of-work state.
--   user_confidence_events      KEEP CASCADE. Per-account scoring signal, meaningless without it.
--   manager_edits.proposed_by   KEEP CASCADE. A proposal is a request from an account, not a
--                               published contribution, and nothing has been attributed to anybody
--                               while it is pending.
--   review_deletions            KEEP CASCADE, and this one is a genuine trade-off. It records that
--                               a contributor deleted a rating of a manager at a time, and rev 268
--                               names it as a TIMING-CORRELATION channel back to an anonymised
--                               rating. Keeping it with a nulled user_id would retain the
--                               correlation without the identity; cascading removes both. Privacy
--                               wins here because the deletion is already recorded on the rating
--                               row itself, so no evidence about the RATING is lost.
--   interview_review_deletions  KEEP CASCADE, same reasoning, and its user_id is NOT NULL so
--                               SET NULL would need a further column change for no gain.
--   banned_users                KEEP CASCADE, AND IT IS A KNOWN HOLE, recorded rather than fixed
--                               here: a banned person can delete their account and register again,
--                               because the ban is keyed on the account. Closing it needs an
--                               identifier that survives deletion (email hash or device), which is
--                               a product and privacy decision of its own, not a cascade setting.


-- ==========================================================================
-- ORIGINALLY V106__one_active_seed_per_manager.sql
-- ==========================================================================

-- PHASE 4, migration 3: at most one ACTIVE synthetic seed per manager.
--
-- A seed (weight = TRUE, user_id = NULL) is a system-generated placeholder that gives a new ghost
-- profile something to show until real ratings arrive. idx_one_seed_per_manager was partial on
-- `weight = TRUE` and NOTHING else, so a soft-deleted seed still occupied the slot.
--
-- THE DEFECT: merging two seeded managers moved every rating onto the survivor, the second seed
-- violated the unique index, and the WHOLE MERGE rolled back. Two genuine duplicate profiles could
-- not be merged at all, and the admin got a constraint name rather than an explanation.
--
-- THE POLICY: keep the survivor's active seed, and keep the absorbed one as non-public history.
--
-- WHY NOT weight = FALSE: that is precisely how this schema says "a real contributor rating".
-- ManagerRepository.describeCareerStage counts real_reviews as NOT (user_id IS NULL AND
-- weight = TRUE) with no deleted_at filter, so a demoted seed would be counted as a genuine
-- contributor opinion on the admin career-stage screen even while soft-deleted, and the only marker
-- saying the row was synthetic would be gone. A synthetic rating is never converted into a real one.

-- The cap is on ACTIVE seeds. A soft-deleted seed is history, not a placeholder, and holding the
-- slot for ever is what made the merge unresolvable without destroying one of them.
DROP INDEX idx_one_seed_per_manager;

CREATE UNIQUE INDEX idx_one_seed_per_manager
    ON reviews (manager_id)
    WHERE weight = TRUE AND deleted_at IS NULL;

-- An explicit, permanent reason, so restoration can never republish a retired seed.
--
-- ReviewRepository's restoration sweep restores only rows with `deleted_reason IS NULL`: a reason
-- means a person or a process decided, and no reason means it may come back. A retired seed must
-- never come back, because the surviving profile already has one and republishing it would violate
-- the index this migration just narrowed. The existing mechanism is therefore exactly what
-- guarantees it, with no new sweep logic.
ALTER TABLE reviews DROP CONSTRAINT reviews_deleted_reason_check;
ALTER TABLE reviews ADD CONSTRAINT reviews_deleted_reason_check
  CHECK (deleted_reason IN ('junk','duplicate','correction','other',
                            'superseded_seed_on_merge'));

-- The merge step that uses this lives in ManagerMergeService, before the statement that moves
-- reviews.manager_id and inside the same transaction, so the losing seed is retired while it still
-- belongs to the absorbed manager and the index is free before any row moves.
--
-- Compatibility verified against every existing seed mechanism:
--   createSeedReview          unaffected; still one active seed per new manager
--   scheduleSeedExpiry        unaffected; targets weight_expires_on IS NULL, and a retired seed is
--                             already soft-deleted
--   cleanup-expired-weight-reviews (pg_cron)
--                             unaffected; matches weight_expires_on <= CURRENT_DATE, and a retired
--                             seed has none
--   overall_rating / reviews_count
--                             unaffected; both filter deleted_at IS NULL
--   LocationStatsProjector / Rebuilder
--                             unaffected; both filter weight = FALSE
--   restoration sweep         cannot republish it, because deleted_reason is not null
--   describeCareerStage       still counts it as a seed, never as a real review, because weight
--                             stays TRUE


-- ==========================================================================
-- ORIGINALLY V107__erased_author_leaves_legacy_dedup.sql
-- ==========================================================================

-- PHASE 4, migration 4: an erased author no longer occupies the legacy dedup key.
--
-- THE DEFECT, and it is a release blocker rather than an edge case.
--
-- idx_reviews_author_manager_role_legacy is unique on
--   (lower(trim(author)), manager_id, lower(trim(manager_title)), lower(trim(manager_company)))
-- for indexed legacy ratings. Account deletion sets author = 'Anonymous User'. So:
--
--   1. Ada and Bea each wrote a legacy rating of the same manager, same title, same company.
--   2. Ada deletes her account. Her rating's author becomes 'Anonymous User'. Fine.
--   3. Bea deletes her account. Her rating's author would also become 'Anonymous User',
--      which collides with Ada's:
--
--      ERROR:  duplicate key value violates unique constraint
--              "idx_reviews_author_manager_role_legacy"
--      DETAIL: Key (...)=(anonymous user, 7, dispatch lead, collide co) already exists.
--
-- **An unrelated person's earlier account deletion prevented Bea from deleting her own.** On a
-- platform carrying sensitive workplace experiences, somebody being unable to erase themselves
-- because a stranger got there first is not an inconvenience.
--
-- Reproduced before fixing, and the reproduction is now a regression test in both repositories.
--
-- WHY A COLUMN RATHER THAN A DIFFERENT AUTHOR STRING. The alternative is minting a unique visible
-- byline per erased rating, e.g. 'anon-1821'. That puts an identifier back on the row the whole
-- erasure exists to de-identify, makes anonymous ratings individually referrable, and changes what
-- the product says to readers. The index's own purpose gives the better answer: it deduplicates
-- IDENTIFIABLE legacy authors, and an erased author is not one. So an erased row leaves the index.
--
-- WHAT IS DELIBERATELY NOT WEAKENED: the index still refuses two ACTIVE legacy ratings that share
-- an author string for the same manager, title and employer, including whitespace and case
-- variants. Verified with both as negative controls. Nothing about duplicate prevention for live
-- ratings changes.

ALTER TABLE reviews
  ADD COLUMN author_erased BOOLEAN NOT NULL DEFAULT FALSE;

DROP INDEX idx_reviews_author_manager_role_legacy;

CREATE UNIQUE INDEX idx_reviews_author_manager_role_legacy
  ON reviews (lower(TRIM(BOTH FROM author)), manager_id,
              lower(TRIM(BOTH FROM COALESCE(manager_title, ''))),
              lower(TRIM(BOTH FROM COALESCE(manager_company, ''))))
  WHERE weight = FALSE
    AND role_basis = 'legacy'
    AND legacy_dedup_state = 'indexed'
    AND NOT author_erased;

-- An erased author implies no account owns the row. The flag cannot be used to exempt a rating
-- that still belongs to somebody, which would be a way to smuggle a duplicate past the index while
-- keeping the contributor attached to it.
--
-- Stated as an implication rather than an equivalence: user_id IS NULL is ordinary for legacy
-- ratings written before accounts existed, and those are exactly the rows the author-string index
-- is there to deduplicate. Only the reverse would be wrong.
ALTER TABLE reviews
  ADD CONSTRAINT review_author_erased_has_no_author
    CHECK (NOT author_erased OR user_id IS NULL);

-- NOT APPLIED to idx_reviews_user_manager_role_legacy, and that is deliberate: it is already
-- partial on user_id IS NOT NULL, so an erased rating leaves it without any help.

-- ---------------------------------------------------------------------------
-- BACKFILL PROVENANCE GATE
-- ---------------------------------------------------------------------------
-- The backfill above claims rows by byline, and a byline is not provenance. The generator cannot
-- produce 'Anonymous User' and the literal is written in one place today, but that does not prove
-- an import, an administrative action or an older release never wrote it.
--
-- So the migration REFUSES TO GUESS: if any row carries that byline while an account is still
-- attached, the literal is demonstrably not exclusive to erasure in this database, and the
-- backfill's premise is false. Failing the migration is correct, because the alternative is
-- silently exempting rows that were never erased from duplicate protection.
--
-- The operator preflight in db/preflight/v107_author_erasure_preflight.sql lists every candidate
-- with its provenance for review before deployment.
DO $$
DECLARE n INT;
BEGIN
  SELECT count(*) INTO n FROM reviews
   WHERE author = 'Anonymous User' AND user_id IS NOT NULL;
  IF n > 0 THEN
    RAISE EXCEPTION
      'V107 preflight: % rating(s) carry the erasure byline while still attached to an account, '
      'so that byline does not exclusively mean erasure in this database. Review them with '
      'db/preflight/v107_author_erasure_preflight.sql before applying this migration.', n;
  END IF;
END $$;

-- ---------------------------------------------------------------------------
-- BACKFILL, before the trigger below, because the trigger would refuse it.
-- ---------------------------------------------------------------------------
-- Ratings already erased by the OLD deletion code carry author = 'Anonymous User' with no account.
-- They are still IN the index, so each one holds that key for its manager, title and employer: a
-- NEW erasure for the same key would be refused all over again, and the defect would survive its
-- own fix for exactly the historical rows most likely to hit it.
--
-- THE PREDICATE IS SAFE AGAINST REVIEWS THAT WERE ANONYMOUS FROM THE START, which is the thing
-- that would change the meaning of historical records. Those do NOT carry this byline: an
-- account-less submission gets a generated pseudonym from ManagerService.generatePseudonym(),
-- which is Adjective + Animal + two digits ("WarmWolf61") and can never produce 'Anonymous User'.
-- The literal is written in exactly one place in either application, the account-deletion path in
-- UserRepository. Verified by grep across both repositories, and by the generator's own shape.
--
-- Deliberately NOT keyed on `user_id IS NULL` alone: that is ordinary for a legacy rating written
-- before accounts existed, and exempting those would silently remove duplicate protection from the
-- rows it was built for. That is the distinction between an ERASED author and an anonymous one.
UPDATE reviews
   SET author_erased = TRUE
 WHERE author = 'Anonymous User'
   AND user_id IS NULL
   AND NOT author_erased;

-- ---------------------------------------------------------------------------
-- AUTHORIZATION: only a real erasure may set the flag
-- ---------------------------------------------------------------------------
-- The CHECK above says an erased rating holds no account. It does NOT say that only a legitimate
-- erasure can set the flag, and those are different claims. Without this, any caller could run
--
--     UPDATE reviews SET author_erased = TRUE WHERE id = ...
--
-- and quietly exempt a rating from duplicate protection, or pre-mark a rating so that a later
-- erasure of somebody else's identical rating behaves differently.
--
-- THE PERMITTED TRANSITION IS A SHAPE, NOT A VALUE: the flag may only go FALSE to TRUE in the same
-- statement that severs the account. `actor_is_erased()` cannot be used here, unlike everywhere
-- else in V105, because this write happens BEFORE the users row is deleted - the anonymisation is
-- step one of the deletion transaction and the delete is step four. What identifies a genuine
-- erasure at this point is not that the account is gone but that it is being taken off this row.
--
-- Three refusals, each of which is a way the flag could otherwise be abused:
--   * setting it on a row that was already account-less  -> an anonymous rating cannot be exempted
--   * setting it while the account stays attached        -> no exemption with an owner still named
--   * clearing it                                        -> an erased row cannot re-enter the index,
--                                                           where it could then collide
CREATE OR REPLACE FUNCTION refuse_unauthorized_author_erasure() RETURNS trigger AS $$
BEGIN
  IF NEW.author_erased AND NOT OLD.author_erased THEN
    IF OLD.user_id IS NULL OR NEW.user_id IS NOT NULL THEN
      RAISE EXCEPTION 'author_erased may only be set while severing the account that owns the rating';
    END IF;
  ELSIF OLD.author_erased AND NOT NEW.author_erased THEN
    RAISE EXCEPTION 'an erased author cannot be restored';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER review_author_erasure_is_authorized
  BEFORE UPDATE OF author_erased, user_id ON reviews
  FOR EACH ROW EXECUTE FUNCTION refuse_unauthorized_author_erasure();

-- And it cannot arrive already set. No account has been severed by an INSERT, so the flag has
-- nothing it could legitimately mean on a new row.
CREATE OR REPLACE FUNCTION refuse_born_erased() RETURNS trigger AS $$
BEGIN
  IF NEW.author_erased THEN
    RAISE EXCEPTION 'a rating cannot be created already erased';
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER review_not_born_erased
  BEFORE INSERT ON reviews
  FOR EACH ROW EXECUTE FUNCTION refuse_born_erased();

-- ---------------------------------------------------------------------------
-- AUTHORIZATION, as opposed to shape
-- ---------------------------------------------------------------------------
-- The trigger above proves the TRANSITION looks like an erasure. It does not prove the update WAS
-- one, and those are different claims. Demonstrated rather than argued: with only that trigger in
-- place, this succeeds while the account is still there.
--
--     UPDATE reviews SET user_id = NULL, author_erased = TRUE WHERE id = ...;
--
-- The prize is small - anyone able to run arbitrary UPDATEs on `reviews` can already null a
-- contributor's id, rewrite their byline or soft-delete the rating, all of which are worse than
-- exempting a row from a dedup index. But "no worse than what was already possible" is not a
-- guarantee, and the flag's whole meaning is that an erasure happened.
--
-- A SESSION FLAG WOULD NOT ESTABLISH ANYTHING: any caller can set_config. Column privileges plus a
-- SECURITY DEFINER entry point would, but only against a NON-OWNER application role, and the role
-- this application connects as is the table owner here. That is an infrastructure change, not a
-- migration, so it is recorded as a proposal rather than assumed.
--
-- WHAT WORKS WITHOUT ANY ROLE SEPARATION: require, at COMMIT, that the account whose id was
-- removed is actually gone. A DEFERRED constraint trigger can ask that question, because by then
-- the whole transaction has happened. The genuine deletion passes because it deletes the account;
-- a forged update does not, and fails at commit.
--
-- WHAT THIS IS, STATED ACCURATELY. It is a DATA-INTEGRITY safeguard against ordinary DML, and it
-- defeats the forgery demonstrated above. It is NOT a security boundary against a privileged
-- database administrator: a superuser or the table owner can ALTER TABLE ... DISABLE TRIGGER, drop
-- it, or change the function, and nothing inside the database can prevent that.
--
-- IT ALSO PROVES A NARROWER FACT THAN IT MIGHT APPEAR TO. It establishes that the account WAS
-- DELETED IN THE SAME TRANSACTION. It does not establish that the deletion was AUTHORIZED - that
-- the person asking had the right to delete that account. Application authorization and database
-- credentials are what establish that, and this trigger does not replace either.
--
-- Within those limits it is still the difference between checking a shape and requiring the event:
-- the flag cannot be set except by a transaction that deletes the account.
CREATE OR REPLACE FUNCTION assert_author_erasure_had_a_deletion() RETURNS trigger AS $$
BEGIN
  IF NEW.author_erased AND NOT OLD.author_erased
     AND EXISTS (SELECT 1 FROM users WHERE id = OLD.user_id) THEN
    RAISE EXCEPTION
      'author_erased was set for review % but account % still exists at commit: the flag may '
      'only be set by a transaction that deletes the account', NEW.id, OLD.user_id;
  END IF;
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER review_author_erasure_needs_account_deletion
  AFTER UPDATE OF author_erased ON reviews
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION assert_author_erasure_had_a_deletion();


-- ==========================================================================
-- ORIGINALLY V108__role_corrections.sql
-- ==========================================================================

-- CANONICAL EMPLOYMENT CORRECTIONS, and the two protections that make them mean anything.
--
-- Rev 268 divides the record four ways ("How the three records divide the work"): testimony is
-- evidence, a claim's assertion is frozen at finalization, claim_corrections is an administrator's
-- determination ABOUT A CLAIM, and claim_resolution_events is the platform's resolution history.
-- The canonical employment row - career_history - appears in none of them. An administrator
-- correcting what the directory asserts today therefore had nowhere to record it, and the only
-- table that looked close is keyed to a single claim, which a canonical row may have many of or
-- none at all.
--
-- The earlier part of the design (around "Corrections to a finalized claim") describes an admin
-- rewriting the claim itself. That region is marked SUPERSEDED in the document's own READ THIS
-- FIRST table, and the later section is explicit: a differing account "leaves the claim's
-- assertion columns untouched, as claim_assertion_is_immutable requires". This migration
-- implements the later rule.

-- ===========================================================================
-- PART 1. A contributor's identity: added once, removed once, NEVER transferred.
-- ===========================================================================
--
-- THE MARKER, and why the rule is unenforceable without it.
--
-- user_id IS NULL means two different things: "nobody has ever adopted this claim" and "somebody
-- did and their account was erased". A trigger comparing OLD to NEW cannot tell them apart, so
-- permitting NULL -> set for adoption and set -> NULL for erasure also permits
-- NULL -> A -> NULL -> B, which is the transfer the rule forbids and the one case that is always
-- an attack. The marker is what makes the two states distinct and the refusal durable.
--
-- The design claimed this immutability without enforcing it anywhere: refuse_assertion_change()
-- and claim_assertion_is_immutable appear in no migration before this one, so until now a
-- finalized claim's title and dates could be rewritten by any statement that cared to.
ALTER TABLE role_claims
  ADD COLUMN contributor_erased BOOLEAN NOT NULL DEFAULT FALSE;

-- Paired both ways, so the marker can never mean anything other than "there was somebody here and
-- they are gone". Anonymous is (NULL, FALSE); erased is (NULL, TRUE); adopted is (uuid, FALSE);
-- (uuid, TRUE) is unrepresentable.
ALTER TABLE role_claims
  ADD CONSTRAINT claim_contributor_erased_is_empty
    CHECK (NOT contributor_erased OR user_id IS NULL);

-- The backfill is FALSE for every existing row, which is correct rather than convenient: per
-- MIGRATION_HISTORY.md this stream (V97 onward) has never been deployed, so no account erasure has
-- ever run against a role_claims row. A past erasure is also undetectable after the fact, the
-- foreign key having nulled the value, so this is stated as the reason rather than asserted by a
-- query that cannot exist.

CREATE OR REPLACE FUNCTION refuse_assertion_change() RETURNS trigger AS $$
DECLARE
  v_clearing boolean := OLD.user_id IS NOT NULL AND NEW.user_id IS NULL;
BEGIN
  IF OLD.contributor_erased AND NOT NEW.contributor_erased THEN
    RAISE EXCEPTION 'an erased contributor cannot be restored';
  END IF;

  -- FABRICATED ERASURE. The CHECK above is satisfied by (NULL, TRUE), so without this an ordinary
  -- UPDATE could stamp any anonymous claim as erased although no account was ever deleted - and
  -- the marker would then refuse that claim's legitimate first adoption for ever. The marker may
  -- only be set in the same statement that clears a real contributor.
  IF NEW.contributor_erased AND NOT OLD.contributor_erased AND NOT v_clearing THEN
    RAISE EXCEPTION
      'contributor_erased is set only when an existing contributor is cleared by account deletion';
  END IF;

  IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
    IF OLD.user_id IS NULL AND NEW.user_id IS NOT NULL THEN
      -- ADOPTION. Permitted once, and never once an erasure has happened here.
      IF OLD.contributor_erased THEN
        RAISE EXCEPTION
          'this claim''s contributor was erased; it cannot be adopted by another account';
      END IF;
    ELSIF v_clearing THEN
      -- ERASURE. Only when the account has actually gone, and it sets the marker in the SAME
      -- statement, which is what makes the next adoption attempt see it. ON DELETE SET NULL
      -- performs an UPDATE, so this fires inside the cascade, by which point the users row is
      -- already gone and actor_is_erased() is true.
      IF NOT actor_is_erased(OLD.user_id) THEN
        RAISE EXCEPTION
          'a contributor may only be cleared when their account has been deleted';
      END IF;
      NEW.contributor_erased := TRUE;
    ELSE
      RAISE EXCEPTION
        'a claim''s contributor may be added once and removed once, never transferred';
    END IF;
  END IF;

  -- THE ASSERTION ITSELF, frozen at finalization. Scoped to finalized claims because an
  -- unfinalized claim is a form somebody is still filling in, and those edits are theirs.
  IF OLD.finalized_at IS NOT NULL
     AND ROW(NEW.proposal_key, NEW.source, NEW.title, NEW.selected_role_id,
             NEW.start_date, NEW.end_date, NEW.period_known, NEW.still_there)
         IS DISTINCT FROM
         ROW(OLD.proposal_key, OLD.source, OLD.title, OLD.selected_role_id,
             OLD.start_date, OLD.end_date, OLD.period_known, OLD.still_there) THEN
    RAISE EXCEPTION
      'a finalized claim''s assertion is what the contributor said and cannot be rewritten; correct the canonical employment instead';
  END IF;

  RETURN NEW;
END; $$ LANGUAGE plpgsql;

-- DELIBERATE DEVIATION from the design's verbatim trigger, which carries
-- WHEN (OLD.finalized_at IS NOT NULL). That clause would leave the IDENTITY rule unenforced on an
-- unfinalized claim, and the erasure cascade reaches those too, so a draft claim could be erased
-- without the marker being set and then adopted by anyone afterwards. The finalization test moves
-- inside the function and guards only the assertion columns, which is the part that is genuinely
-- about finalization. Approved as a deviation rather than taken quietly.
CREATE TRIGGER claim_assertion_is_immutable
  BEFORE UPDATE OF proposal_key, source, user_id, title, selected_role_id,
                   start_date, end_date, period_known, still_there, contributor_erased
  ON role_claims
  FOR EACH ROW
  EXECUTE FUNCTION refuse_assertion_change();

-- ===========================================================================
-- PART 2. The canonical correction record.
-- ===========================================================================
--
-- One row per FIELD, as claim_corrections does, so "what changed" is answerable field by field
-- rather than by diffing two blobs.
--
-- company_id IS DELIBERATELY ABSENT from the permitted fields.
-- reviews_withdrawn_role_is_the_managers ties (withdrawn_role_id, manager_id) to
-- career_history (id, manager_id) and does NOT carry company_id, so changing a role's employer
-- would silently re-attribute any rating held against it with no constraint objecting. A role with
-- no claims is therefore NOT a role with no ratings. Employer changes wait for the supersession
-- design, where state = 'superseded' with superseded_by expresses "this record was wrong and this
-- one replaces it" without rewriting a single assertion.
--
-- ON DELETE RESTRICT, not CASCADE: a corrected employment must not be deletable out from under its
-- own audit. ManagerMergeService.mergeRoles SUPERSEDES rather than deletes - it sets
-- state = 'superseded' and superseded_by - so a merge is unaffected and correction history
-- survives it. AdminService.deleteCareerEntry does delete, and will now be refused for a corrected
-- role; that refusal is deliberate and the service translates it rather than leaking the
-- constraint error.
CREATE TABLE role_corrections (
  id              BIGSERIAL   PRIMARY KEY,
  role_id         BIGINT      NOT NULL REFERENCES career_history(id) ON DELETE RESTRICT,
  field           TEXT        NOT NULL,
  was             TEXT,
  now_is          TEXT,
  reason          TEXT        NOT NULL,

  -- Nullable with a companion flag, exactly as V105 reshaped claim_corrections,
  -- manager_merge_events and company_merges, so an erasure can clear the actor without deleting
  -- the record of what was done.
  corrected_by    UUID        REFERENCES users(id) ON DELETE SET NULL,
  actor_withdrawn BOOLEAN     NOT NULL DEFAULT FALSE,
  corrected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- The four fields that have a canonical home AND are safe to change in place. still_there is
  -- absent because career_history has no such column and role_claims.still_there is the
  -- contributor's assertion, not a statement about today. state is absent because accept, reject
  -- and reopen are lifecycle transitions that claim_resolution_events and
  -- career_history.reopened_from already record.
  CONSTRAINT role_correction_field_known
    CHECK (field IN ('title','start_date','end_date','period_known')),

  -- A correction that changed nothing is noise in the one place that has to stay readable.
  CONSTRAINT role_correction_changed_something
    CHECK (was IS DISTINCT FROM now_is),

  -- A reason is the whole point: without it the history says what but never why.
  CONSTRAINT role_correction_reason_given
    CHECK (btrim(reason) <> ''),

  CONSTRAINT role_correction_actor_absent_is_withdrawn
    CHECK ((corrected_by IS NULL) = actor_withdrawn)
);

CREATE INDEX role_corrections_role ON role_corrections (role_id, id);

-- ===========================================================================
-- PART 3. Append-only and erasure-compatible, in ONE function.
-- ===========================================================================
--
-- ONE trigger rather than two, so there is no firing order to get right. Two separate BEFORE
-- UPDATE triggers would have to agree about whether the other had already set actor_withdrawn, and
-- which runs first depends on their names: rename either and erasure starts failing with "this
-- table is append-only" for no visible reason. Here the same function both authorises the erasure
-- and sets the flag, so the byte-identical comparison never has to reason about a value another
-- trigger may or may not have written.
CREATE OR REPLACE FUNCTION refuse_role_correction_change() RETURNS trigger AS $$
BEGIN
  -- INSERT IS CHECKED TOO. The CHECK constraint alone cannot prove a correction ever had an
  -- administrator, because (NULL, TRUE) satisfies it: an insert could fabricate an actor who had
  -- already been erased, and the audit would then legitimately name nobody. V105 asserts in a
  -- comment that "an INSERT with no actor is still refused", and for a CHECK alone that is not
  -- true. claim_corrections, manager_merge_events and company_merges have the same gap; closing
  -- those is a separate change and is not bundled here.
  IF TG_OP = 'INSERT' THEN
    IF NEW.corrected_by IS NULL OR NEW.actor_withdrawn THEN
      RAISE EXCEPTION 'a correction must name the administrator who made it';
    END IF;
    RETURN NEW;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'this table is append-only';
  END IF;

  IF NEW.corrected_by IS NULL AND OLD.corrected_by IS NOT NULL THEN
    IF NOT actor_is_erased(OLD.corrected_by) THEN
      RAISE EXCEPTION 'an actor may only be cleared when their account has been deleted';
    END IF;
    IF ROW(NEW.id, NEW.role_id, NEW.field, NEW.was, NEW.now_is,
           NEW.reason, NEW.corrected_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.role_id, OLD.field, OLD.was, OLD.now_is,
           OLD.reason, OLD.corrected_at) THEN
      RAISE EXCEPTION 'this table is append-only, except clearing corrected_by';
    END IF;
    NEW.actor_withdrawn := TRUE;
    RETURN NEW;
  END IF;

  -- Everything else, including reassigning the actor to a different account.
  RAISE EXCEPTION 'this table is append-only, except clearing corrected_by';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER role_corrections_append_only
  BEFORE INSERT OR UPDATE OR DELETE ON role_corrections
  FOR EACH ROW EXECUTE FUNCTION refuse_role_correction_change();


-- ==========================================================================
-- ORIGINALLY V109__audit_actor_begins_present.sql
-- ==========================================================================

-- AN AUDIT RECORD NAMES ITS ACTOR WHEN IT IS BORN.
--
-- V105 gave claim_corrections, manager_merge_events and company_merges a nullable actor with an
-- actor_withdrawn companion, so a legitimate account deletion can remove WHO without deleting the
-- record of WHAT. Its CHECK ties the two together:
--
--     CHECK ((corrected_by IS NULL) = actor_withdrawn)
--
-- and V105's comment states that this refuses an actorless INSERT: "an INSERT with no actor is
-- still refused, so 'nobody did this' never becomes representable". For a CHECK by itself that is
-- not true. (NULL, TRUE) satisfies it, so a row can be BORN already-withdrawn and the history then
-- names nobody with every constraint satisfied. V108 closed the same hole in role_corrections by
-- extending its trigger to cover INSERT; these three predate it.
--
-- WHAT THIS DELIBERATELY DOES NOT DO: it does not inspect, reject or alter a single historical
-- row. An existing (NULL, TRUE) row is exactly what V105 produces when an administrator deletes
-- their account, and those two columns cannot distinguish that from a fabricated insert. A
-- migration that refused every such row would reject valid erasures. Counting them is a read-only
-- operator question, in db/preflight/v109_withdrawn_actors.sql, not a gate on this migration.

-- to_jsonb(NEW) RATHER THAN DYNAMIC SQL. The three tables name their actor column differently
-- (corrected_by, merged_by, merged_by), so one shared function has to read a column chosen at
-- runtime. An EXECUTE format('SELECT ($1).%I', TG_ARGV[0]) USING NEW would do it, and adds a
-- planner round trip and a quoting surface to a check that is two null tests. A record converts
-- to jsonb directly, so the field lookup is an ordinary operator.
CREATE OR REPLACE FUNCTION refuse_actorless_audit_insert() RETURNS trigger AS $$
DECLARE
  v_row JSONB := to_jsonb(NEW);
BEGIN
  IF v_row ->> TG_ARGV[0] IS NULL
     OR COALESCE((v_row ->> 'actor_withdrawn')::boolean, FALSE) THEN
    RAISE EXCEPTION
      'an audit record must name the actor who made it; % may only be cleared by a later erasure',
      TG_ARGV[0];
  END IF;
  RETURN NEW;
END; $$ LANGUAGE plpgsql;

-- INSERT ONLY. Every existing BEFORE UPDATE trigger that clears an actor on erasure, and the
-- foreign key's ON DELETE SET NULL that drives it, are untouched.
CREATE TRIGGER claim_corrections_begin_with_an_actor
  BEFORE INSERT ON claim_corrections
  FOR EACH ROW EXECUTE FUNCTION refuse_actorless_audit_insert('corrected_by');

CREATE TRIGGER manager_merge_events_begin_with_an_actor
  BEFORE INSERT ON manager_merge_events
  FOR EACH ROW EXECUTE FUNCTION refuse_actorless_audit_insert('merged_by');

CREATE TRIGGER company_merges_begin_with_an_actor
  BEFORE INSERT ON company_merges
  FOR EACH ROW EXECUTE FUNCTION refuse_actorless_audit_insert('merged_by');


-- ==========================================================================
-- ORIGINALLY V110__contribution_drafts.sql
-- ==========================================================================

-- A signed-in contributor's unfinished rating, private to them until they submit it.
--
-- Deliberately NOT captured_drafts. That table exists so an ABANDONED ANONYMOUS contribution
-- reaches moderation, and the admin queue reads it. This one exists so a person can resume their
-- own work on another device, and moderation must never see it: submission is the boundary at
-- which a rating enters the normal lifecycle. Two purposes, two readers, two lifecycles, two
-- erasure rules - so two tables, and the isolation is structural rather than a predicate that
-- every present and future query has to remember.
--
-- Additive and RMM-compatible by construction: a brand-new table, no change to any shared table,
-- and RMM neither reads nor writes it. Nothing in either application can be invalidated by this
-- file.
--
-- Design: PROPOSAL_PRIORITY2_DRAFT_RESTORATION.md revision 5, approved. The SQL below was
-- executed against this schema at version 109 in a rolled-back transaction before being written
-- here, including that a draft SURVIVES physical deletion of its subject and is removed with its
-- account.

CREATE TABLE contribution_drafts (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- The subject taxonomy rating_opportunities already uses, rather than a second one. There is
    -- no separate `kind`: it was a bijection with subject_type, and two columns that must agree
    -- are two columns that can disagree.
    subject_type  TEXT        NOT NULL,
    -- NO FOREIGN KEY, deliberately, exactly as captured_drafts carries none on its subject. A
    -- draft must survive its subject: a draft for a subject that can no longer be rated stays
    -- visible, marked unavailable. A merge does not delete the subject anyway - it sets
    -- approval_status='rejected' and merged_into - so availability and the redirect are read from
    -- the subject at read time, and a physically deleted subject reads as unavailable rather than
    -- silently taking the contributor's work with it.
    subject_id    BIGINT      NOT NULL,
    -- Which of the person's two rating slots this draft is for. ordinal IN (1,2) matches
    -- opportunity_ordinal_bounded: somebody holding two opportunities for one subject may
    -- legitimately be drafting the second, so the uniqueness key cannot stop at the subject.
    ordinal       SMALLINT    NOT NULL DEFAULT 1,

    payload       JSONB       NOT NULL,

    version       BIGINT      NOT NULL DEFAULT 1,
    -- The write that produced this version. A retry of a write whose acknowledgement was lost
    -- carries the SAME write_id, so the server recognises a replay and answers with the stored
    -- version instead of a false conflict. A different device's write carries a different one.
    write_id      UUID        NOT NULL,

    -- The anonymous captured_drafts form this draft continues, when it continues one. A WRITE
    -- RESOLUTION KEY ONLY: it lets the eventual submission find and remove an anonymous copy a
    -- moderator published in the meantime, which is the V96 rule. It authorizes nothing, and no
    -- read path accepts it.
    capture_token UUID,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT contribution_draft_subject_known
        CHECK (subject_type IN ('manager', 'company', 'interview')),
    CONSTRAINT contribution_draft_ordinal_bounded
        CHECK (ordinal IN (1, 2)),
    CONSTRAINT contribution_draft_version_positive
        CHECK (version > 0)
);

-- One draft per person per subject per slot, keyed exactly as opportunity_one_per_ordinal is.
CREATE UNIQUE INDEX contribution_drafts_one_per_slot
    ON contribution_drafts (owner_id, subject_type, subject_id, ordinal);

-- One account may continue one anonymous capture. Without this, two accounts could both claim the
-- same capture and both submit against it.
CREATE UNIQUE INDEX contribution_drafts_capture_token
    ON contribution_drafts (capture_token) WHERE capture_token IS NOT NULL;

-- The account's Unfinished Ratings list.
CREATE INDEX contribution_drafts_owner_recent
    ON contribution_drafts (owner_id, updated_at DESC);

-- The 90-day retention sweep reads this.
CREATE INDEX contribution_drafts_stale
    ON contribution_drafts (updated_at);

-- Ownership is immutable, and the version only ever advances by one.
--
-- Both in the database rather than in Java, because the whole privacy guarantee rests on them. A
-- filter in a repository is a promise; a trigger is a rule.
--
-- owner_id cannot change at all. There is no legitimate transfer: a draft belongs to the person
-- who wrote it, account deletion removes it, and continuing an anonymous capture is a WRITE by the
-- new owner's own session rather than a change of owner on an existing row.
--
-- version must advance by exactly one. Monotonic alone would let a client jump ahead and defeat
-- the optimistic check; exactly-one makes the check meaningful.
CREATE OR REPLACE FUNCTION refuse_draft_identity_change() RETURNS trigger AS $$
BEGIN
    IF NEW.owner_id <> OLD.owner_id THEN
        RAISE EXCEPTION
            'a contribution draft belongs to the account that wrote it and cannot be transferred';
    END IF;
    IF NEW.subject_type <> OLD.subject_type
       OR NEW.subject_id <> OLD.subject_id
       OR NEW.ordinal <> OLD.ordinal THEN
        RAISE EXCEPTION
            'a contribution draft is about one subject in one slot; start a new draft instead';
    END IF;
    IF NEW.capture_token IS DISTINCT FROM OLD.capture_token THEN
        RAISE EXCEPTION
            'capture_token records which anonymous capture this draft continues and is set once';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION
            'contribution_drafts.version advances by exactly one per accepted write (% to %)',
            OLD.version, NEW.version;
    END IF;
    IF NEW.write_id = OLD.write_id THEN
        RAISE EXCEPTION
            'a replayed write must be answered from the stored version, not applied again';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER contribution_draft_identity_is_immutable
    BEFORE UPDATE ON contribution_drafts
    FOR EACH ROW EXECUTE FUNCTION refuse_draft_identity_change();

COMMENT ON TABLE contribution_drafts IS
    'A signed-in contributor''s unfinished rating, private to them until they submit it. NEVER '
    'read by moderation and never aggregated: an anonymous abandoned contribution belongs in '
    'captured_drafts, which moderation does read. Deleted outright when the account is deleted, '
    'and swept 90 days after the last accepted save.';

COMMENT ON COLUMN contribution_drafts.version IS
    'Server-assigned, advancing by exactly one per accepted write. The client sends the version it '
    'last saw and the write is refused unless it still matches, which is what stops a stale '
    'device overwriting newer answers. Client clocks are not authoritative.';

COMMENT ON COLUMN contribution_drafts.write_id IS
    'The write that produced the current version. A retry of a write whose acknowledgement never '
    'arrived carries the same value, so a replay is answered from the stored version instead of '
    'reported as a cross-device conflict that never happened.';

COMMENT ON COLUMN contribution_drafts.capture_token IS
    'The anonymous captured_drafts form this draft continues, when it continues one. A write '
    'resolution key only, so the eventual submission can remove an anonymous copy a moderator '
    'published meanwhile (V96). It authorizes nothing and no read path accepts it.';


-- ==========================================================================
-- ORIGINALLY V111__testimony_and_withdrawal.sql
-- ==========================================================================

-- ===========================================================================
-- V111 — contributor testimony, the claim tombstone, one more deletion reason,
-- and the legacy dedup index narrowed so a permanently dead rating leaves it.
--
-- Approved 2026-11-03, all four parts, after an index-parity check: the
-- recreated unique index was compared against pg_get_indexdef() of the
-- installed one, token by token, and the ONLY difference is the intended
-- "AND (deleted_reason IS NULL)". The widened CHECK preserves all five
-- previously accepted values in order.
--
-- Werkpages owns this migration. RMM is frozen at V43 and reads the result.
--
-- Every statement is additive or widening:
--   * a new table RMM has never heard of;
--   * a new NULLable column with no default;
--   * a CHECK that admits one more value than it did;
--   * an index predicate that only ever removes rows from the index, so
--     nothing that inserts today can start failing.
-- No RMM write path can be broken by any of them, and no existing row can
-- violate any of them.
--
-- PARTS 3 AND 4 ARE ONE CHANGE. The replacement frees its own dedup slot by
-- naming itself replaced_by_author in the same statement that deletes the
-- original, before the replacement row is inserted. Either part alone leaves
-- the replacement lifecycle broken: see OPEN_ISSUES.md issues 37 and 38.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. THE CLAIM TOMBSTONE
-- ---------------------------------------------------------------------------
-- Rev 268, verbatim. Withdrawal does not delete a claim and does not sever it
-- from its contributor: the claim survives as a tombstone with user_id and
-- finalized_at intact, withdrawn_at set, and pointing at no rating. That is
-- what stops the same person rating the same employment twice, and it is why
-- the uniqueness index is NOT made partial on withdrawn_at: a partial index
-- would permit a second claim for one employment, and one contributor holding
-- two claims on one employment during the restoration window is the double
-- count again by another route.
--
-- Revival clears it. refuse_assertion_change() already permits that: its
-- immutable ROW() names proposal_key, source, title, selected_role_id,
-- start_date, end_date, period_known and still_there, and withdrawn_at is not
-- among them. No trigger change is needed.

ALTER TABLE role_claims ADD COLUMN withdrawn_at TIMESTAMPTZ;

CREATE INDEX role_claims_withdrawn ON role_claims (withdrawn_at)
  WHERE withdrawn_at IS NOT NULL;

COMMENT ON COLUMN role_claims.withdrawn_at IS
  'Set when the contributor''s rating is anonymised at restoration, NOT when they withdraw it. '
  'The claim keeps user_id and finalized_at and points at no rating: a tombstone, not corruption. '
  'Cleared by revival, which is how the same contributor re-rates that employment.';


-- ---------------------------------------------------------------------------
-- 2. CONTRIBUTOR TESTIMONY
-- ---------------------------------------------------------------------------
-- APPEND-ONLY. Every employment statement a contributor has ever made about a
-- claim, including the one that created it. Nothing here is authoritative;
-- this is the evidence the resolution is drawn FROM.
--
-- Why this is not claim_corrections, which rev 268 is explicit about: an
-- administrative correction is a determination that the claim was wrong. A
-- contributor remembering it differently two years later is a second
-- observation, and the first does not become false because a later one exists.
-- Filing the second as a correction would assert that the platform has decided
-- when nobody has, and would discard the first as superseded. No contributor
-- path writes claim_corrections.
--
-- arrived_with_review_id IS DELIBERATELY ABSENT. Rev 259 specified it and rev
-- 260 removed it as a privacy defect: a column holding a review id in a row
-- that also holds user_id reconstructs the authorship of an anonymised rating
-- in one query, and "the reference points the other way" is not a defence
-- because a join has no direction. claim_id, user_id and said_at preserve
-- everything the testimony actually needs, and the series is ordered by
-- said_at, which never needed a rating to order it.

CREATE TABLE claim_testimony (
  id          BIGSERIAL PRIMARY KEY,
  claim_id    BIGINT NOT NULL REFERENCES role_claims(id) ON DELETE CASCADE,

  -- The contributor who said it. Present because testimony is attributable by
  -- nature. SET NULL, not CASCADE: deleting an account removes the name and
  -- keeps the statement, which is the same rule V105 applies everywhere else.
  -- The UPDATE this performs fires the append-only trigger below, which is
  -- written to permit exactly that one shape.
  user_id     UUID REFERENCES users(id) ON DELETE SET NULL,

  -- WHAT THEY SAID, in their words, at this moment. DATE rather than the
  -- design's TIMESTAMPTZ, so these are the same type as the role_claims
  -- assertion columns they mirror and the design's first test -- "the initial
  -- testimony row matches the assertion columns" -- is a plain equality rather
  -- than a conversion. Flagged for approval; claim_testimony has no extract in
  -- authoritative/, so no drift check constrains the choice.
  title         TEXT,
  company_name  TEXT,
  start_date    DATE,
  end_date      DATE,
  period_known  BOOLEAN,
  still_there   BOOLEAN,

  -- WHY IT WAS RECORDED. 'initial' is the submission that created the claim;
  -- 'repeat' is the same contributor submitting again later.
  occasion    TEXT NOT NULL,
  said_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT testimony_occasion_known CHECK (occasion IN ('initial','repeat')),
  -- At least one thing must actually be asserted, or the row records nothing.
  CONSTRAINT testimony_says_something CHECK (
    title IS NOT NULL OR company_name IS NOT NULL OR start_date IS NOT NULL
    OR end_date IS NOT NULL OR period_known IS NOT NULL OR still_there IS NOT NULL)
);

CREATE INDEX claim_testimony_claim ON claim_testimony (claim_id, said_at DESC);

-- Permits EXACTLY one UPDATE shape: user_id cleared, every other column
-- byte-identical, which is the account-deletion cascade and nothing else.
-- DELETE is refused always. Transcribed from authoritative/
-- refuse_history_change_except_erasure.sql, which 99_drift.sh compares against
-- the installed object, so the body must not be edited.
--
-- V105 wrote refuse_event_change_except_erasure() and
-- refuse_supersession_change_except_erasure() rather than reusing this one,
-- because a ROW() comparison has to name every column it holds constant and
-- those two tables have different columns and erase "by" instead of user_id.
-- This is the third of the three, and the one the design wrote it for.
CREATE OR REPLACE FUNCTION refuse_history_change_except_erasure() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'this table is append-only';
  END IF;
  IF NEW.user_id IS NULL AND OLD.user_id IS NOT NULL
     AND ROW(NEW.id, NEW.claim_id, NEW.title, NEW.company_name, NEW.start_date,
             NEW.end_date, NEW.period_known, NEW.still_there, NEW.occasion,
             NEW.said_at)
       IS NOT DISTINCT FROM
         ROW(OLD.id, OLD.claim_id, OLD.title, OLD.company_name, OLD.start_date,
             OLD.end_date, OLD.period_known, OLD.still_there, OLD.occasion,
             OLD.said_at) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'this table is append-only, except clearing user_id';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER claim_testimony_append_only
  BEFORE UPDATE OR DELETE ON claim_testimony
  FOR EACH ROW EXECUTE FUNCTION refuse_history_change_except_erasure();

COMMENT ON TABLE claim_testimony IS
  'Append-only. Every employment statement a contributor has made about a claim, including the '
  'one that created it. Evidence, never authoritative: the platform''s determination lives in '
  'claim_corrections, which no contributor path writes, and its resolution in '
  'claim_resolution_events. No column here may ever hold a rating id.';


-- ---------------------------------------------------------------------------
-- 3. ONE MORE DELETION REASON
-- ---------------------------------------------------------------------------
-- Issue 37. An author replacing their own rating soft-deletes the original
-- with no deleted_reason, and the restoration sweep restores precisely the
-- rows that carry no reason, so three days after any edit the original comes
-- back live, anonymous and counted beside its replacement.
--
-- deleted_reason IS NULL is the right predicate and is not changed: it is the
-- only thing separating an author's withdrawal from a moderator's removal, and
-- that distinction was itself a fix for the sweep resurrecting actioned junk.
-- A replaced original simply is not a withdrawal, and rev 268 names it.
--
-- 'other' was considered and rejected: it files an author's edit as an
-- administrative deletion in every admin surface that reads the reason, and
-- the next person to read the column could not tell the two apart.
--
-- Widening a CHECK cannot fail on existing data and cannot break an RMM write
-- path, since RMM writes none of these values until its own code changes.

ALTER TABLE reviews
  DROP CONSTRAINT reviews_deleted_reason_check,
  ADD CONSTRAINT reviews_deleted_reason_check
    CHECK (deleted_reason IN ('junk','duplicate','correction','other',
                              'superseded_seed_on_merge','replaced_by_author'));


-- ---------------------------------------------------------------------------
-- 4. A PERMANENTLY DEAD RATING LEAVES THE LEGACY DEDUP INDEX
-- ---------------------------------------------------------------------------
-- Issue 38, found while writing issue 37's regression test and NOT a production
-- defect today, because V97 to V103 are not deployed. It becomes real the day
-- V102 ships, which makes it a deployment blocker.
--
-- idx_reviews_author_manager_role_legacy is unique on
--   (lower(author), manager_id, lower(manager_title), lower(manager_company))
-- for indexed legacy ratings, and carries NO deleted_at predicate. A
-- soft-deleted row keeps occupying its slot, so replacing a rating WITHOUT
-- changing the manager's title or employer -- the ordinary case, since the form
-- prefills both from the profile -- fails outright:
--
--   duplicate key value violates unique constraint
--   "idx_reviews_author_manager_role_legacy"
--
-- Measured, not predicted: that is what the first run of
-- anAuthorsReplacedRatingMustNotComeBackThreeDaysLater did before it was
-- narrowed to a replacement that changes the title.
--
-- WHY THE PREDICATE IS NOT "deleted_at IS NULL". A withdrawn rating COMES BACK
-- after three days. If it left the index while deleted, an identical rating
-- could be inserted meanwhile, and the restoration sweep -- one bulk UPDATE --
-- would abort on the collision for every row in its batch. The slot has to stay
-- held for a row that is returning.
--
-- Only a row that can NEVER return may leave, and that is exactly a row
-- carrying a deleted_reason: a moderated removal, a seed superseded by a merge,
-- or an author's own replacement. So:
--
--   deleted_reason IS NULL  (a withdrawal)        -> stays indexed, slot held
--   deleted_reason present  (never coming back)   -> leaves, slot freed
--
-- which also means part 3 and this part only work together: the replacement
-- frees its own slot by naming itself replaced_by_author, in the same statement
-- that deletes the original, before the new row is inserted.
--
-- THE USER-KEYED INDEX IS DELIBERATELY NOT CHANGED. idx_reviews_user_manager_
-- role_legacy is partial on user_id IS NOT NULL, and ReviewRepository.delete
-- nulls user_id, so a deleted legacy row is already outside it. V102's own
-- "NOTE THE ASYMMETRY" comment is about this pair; adding a predicate that
-- cannot match anything would make them look alike while behaving differently.

DROP INDEX idx_reviews_author_manager_role_legacy;

CREATE UNIQUE INDEX idx_reviews_author_manager_role_legacy
  ON reviews (lower(trim(author)), manager_id,
              lower(trim(coalesce(manager_title, ''))),
              lower(trim(coalesce(manager_company, ''))))
  WHERE weight = FALSE
    AND role_basis = 'legacy'
    AND legacy_dedup_state = 'indexed'
    AND NOT author_erased
    AND deleted_reason IS NULL;


-- ==========================================================================
-- ORIGINALLY V112__employment_status.sql
-- ==========================================================================

-- ===========================================================================
-- V112 — the employment-status model: the resolved status on career_history,
-- its append-only event log, the employment_status_dispute conflict kind, and
-- the three rules as functions.
--
-- Approved 2026-11-09, with all five stated deviations from rev 268 approved
-- explicitly. Decision P8, answered the same day: employment status ships WITH
-- the identity redesign, not after cutover.
--
-- Rev 268 authority: "The resolved employment-status model" and "The
-- evidence-freshness policy", both listed as authoritative and unamended in
-- that document's own header table. Superseded and NOT implemented here:
-- "employment status from the most recent still_there", and case 5's "most
-- recent assertion wins", which rev 251's incompatibility rule replaces.
--
-- Werkpages owns this migration. RMM is frozen at V43 and reads the result.
--
-- RMM COMPATIBILITY, every statement checked against CLAUDE.md section 10:
--   * six new NULLable columns on career_history;
--   * employment_status is NOT NULL WITH A DEFAULT, so an RMM INSERT that
--     omits it still works. Section 10 forbids NOT NULL without a default;
--   * two CHECK constraints WIDENED, admitting one more value each. No
--     existing row and no current write path can violate a widened CHECK;
--   * one new table RMM has never heard of;
--   * three new functions, two new triggers, touching no existing column;
--   * a backfill that writes only the new columns.
-- No RMM read or write path can break on any of it.
--
-- PUBLICATION IS UNCHANGED. kind_blocks_publication is not touched, so an
-- employment_status_dispute is non-blocking: blocks_publication is a STORED
-- generated column, every dispute row is born false, and no backfill is
-- needed. Rev 268's reason, verbatim: the dispute "says nothing about whether
-- a rating may be published, and blocking publication on it would withhold
-- ratings over a question that has no bearing on them".
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. WHAT career_history GAINS
-- ---------------------------------------------------------------------------
-- The platform's RESOLVED status, its basis, and THE DATE THE EVIDENCE SPEAKS
-- TO. Not the date we wrote it down: an 'ongoing' resolution grounded in a
-- contributor who left in 2021 has status_as_of = 2021, and ages from there.
--
-- status_claim_id is a deviation from the design text and the reason is in the
-- proposal: the design has the event log written by "a trigger on the above",
-- and a trigger can only see the row it fires for. Without the evidence
-- pointer and the actor ON the row, the log would have to be written by the
-- application, which means a path that forgets it writes no event at all.
-- With them, the log is impossible to bypass.

ALTER TABLE career_history
  ADD COLUMN employment_status  TEXT NOT NULL DEFAULT 'unknown',
  ADD COLUMN status_as_of       TIMESTAMPTZ,
  ADD COLUMN status_basis       TEXT,
  ADD COLUMN status_resolved_at TIMESTAMPTZ,
  ADD COLUMN status_resolved_by UUID   REFERENCES users(id)      ON DELETE SET NULL,
  ADD COLUMN status_claim_id    BIGINT REFERENCES role_claims(id) ON DELETE SET NULL,
  -- Set when somebody re-checks and the answer holds. Distinct from
  -- status_resolved_at, which records when it was first established.
  ADD COLUMN status_verified_at TIMESTAMPTZ,

  ADD CONSTRAINT employment_status_known CHECK (employment_status IN
    ('ongoing','ended','unknown','disputed')),
  ADD CONSTRAINT employment_status_basis_known CHECK (status_basis IS NULL OR status_basis IN
    ('end_date','contributor_claim','moderator','employer_confirmed','no_evidence')),

  -- A resolution that ASSERTS something must say what it speaks to and where
  -- it came from. 'unknown' and 'disputed' assert nothing and need neither.
  ADD CONSTRAINT employment_status_is_grounded CHECK (
    employment_status IN ('unknown','disputed')
    OR (status_as_of IS NOT NULL AND status_basis IS NOT NULL
        AND status_resolved_at IS NOT NULL)),

  -- Nothing can be re-verified before it was resolved. Cheap, and it stops a
  -- verification sweep writing a confirmation onto a row holding no claim.
  ADD CONSTRAINT employment_status_verified_after_resolution CHECK (
    status_verified_at IS NULL OR status_resolved_at IS NOT NULL),

  -- 'contributor_claim' is the only basis that NAMES a claim, and it must.
  -- Without this a contributor-grounded status could carry no evidence
  -- pointer, and the event log would record a resolution with no source.
  ADD CONSTRAINT employment_status_claim_basis_pairs CHECK (
    (status_basis IS NOT DISTINCT FROM 'contributor_claim')
    = (status_claim_id IS NOT NULL));

COMMENT ON COLUMN career_history.status_as_of IS
  'THE DATE THE EVIDENCE SPEAKS TO, not when it was recorded. Where status_basis is '
  '''contributor_claim'' this is the contributor''s own end date and is therefore NEVER public: '
  'see EMPLOYMENT_DATE_PRIVACY_CONTRACT.md. Public surfaces emit the presented label only.';


-- ---------------------------------------------------------------------------
-- 2. THE BACKFILL, before the trigger exists
-- ---------------------------------------------------------------------------
-- Every accepted role that already carries an end date IS an ended employment,
-- and the evidence is the record itself. Leaving them 'unknown' would make the
-- model answer Unknown for the entire imported history on day one.
--
-- Deliberately BEFORE the event trigger is created, so the backfill writes no
-- events: the log begins at this migration and records every change after it.
-- One event per backfilled row would be tens of thousands of rows recording
-- "the migration ran", which is what the migration itself records.
--
-- NOT touched: rows with no end date. A null end date is the ABSENCE of
-- information and is never evidence of ongoing employment. That asymmetry is
-- the design's and it is deliberate.

UPDATE career_history
   SET employment_status  = 'ended',
       status_as_of       = end_date,
       status_basis       = 'end_date',
       status_resolved_at = now()
 WHERE state = 'accepted' AND end_date IS NOT NULL;

DO $$
DECLARE n BIGINT;
BEGIN
  SELECT count(*) INTO n FROM career_history
   WHERE state = 'accepted' AND end_date IS NOT NULL AND employment_status <> 'ended';
  IF n > 0 THEN
    RAISE EXCEPTION 'V112 backfill left % accepted ended roles unresolved', n;
  END IF;
  -- The grounding CHECK is already enforcing this; asserted anyway because a
  -- silent miss here is invisible until a profile reads Unknown for a job that
  -- plainly finished.
  SELECT count(*) INTO n FROM career_history
   WHERE employment_status = 'ended' AND (status_as_of IS NULL OR status_basis IS NULL);
  IF n > 0 THEN
    RAISE EXCEPTION 'V112 backfill left % ended roles ungrounded', n;
  END IF;
END $$;


-- ---------------------------------------------------------------------------
-- 3. THE EVENT LOG
-- ---------------------------------------------------------------------------
-- Append-only. Every change to the resolution, with the evidence BY REFERENCE
-- rather than copied, which is how provenance is kept without a second copy of
-- the contributor's words.
--
-- ON DELETE RESTRICT on role_id, which DEVIATES from the design's CASCADE.
-- Flagged for approval. The reason is this codebase's own executed precedent:
-- V103 chose SET NULL over CASCADE because "deleting a manager would have
-- erased the record that their employment was ever disputed", and nothing in
-- this design deletes a career_history row. RESTRICT makes that structural
-- instead of a convention somebody later forgets. If a genuine need to delete
-- a role ever appears, it arrives with its own migration and its own decision.
--
-- "by" IS NULLABLE AND HAS NO ACTOR TRIGGER, which also deviates: V109 gives
-- claim_corrections, manager_merge_events and company_merges a
-- refuse_actorless_audit_insert guard. This table must not have one. Most
-- status resolutions have NO human actor at all: a contributor's claim
-- resolving, or an end date being accepted, is the resolution component
-- acting, and inventing an actor for it would be a fabrication with a column
-- name attached. The basis column is what records provenance here, and it is
-- NOT NULL in every asserting state by the grounding CHECK above.

CREATE TABLE employment_status_events (
  id          BIGSERIAL   PRIMARY KEY,
  role_id     BIGINT      NOT NULL REFERENCES career_history(id) ON DELETE RESTRICT,
  from_status TEXT,
  to_status   TEXT        NOT NULL,
  basis       TEXT,
  as_of       TIMESTAMPTZ,
  -- THE EVIDENCE, by reference. A claim id, never a rating id: see the rev 260
  -- privacy correction and the rule it generalised. career_history carries no
  -- contributor column, so this adds no path from an anonymised rating.
  claim_id    BIGINT      REFERENCES role_claims(id) ON DELETE SET NULL,
  at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  "by"        UUID        REFERENCES users(id) ON DELETE SET NULL,

  CONSTRAINT status_event_to_known   CHECK (to_status IN
    ('ongoing','ended','unknown','disputed')),
  CONSTRAINT status_event_from_known CHECK (from_status IS NULL OR from_status IN
    ('ongoing','ended','unknown','disputed')),
  CONSTRAINT status_event_basis_known CHECK (basis IS NULL OR basis IN
    ('end_date','contributor_claim','moderator','employer_confirmed','no_evidence'))
);

CREATE INDEX employment_status_events_role ON employment_status_events (role_id, at);
CREATE INDEX employment_status_events_claim ON employment_status_events (claim_id);

-- The same shape as refuse_event_change_except_erasure: exactly one UPDATE is
-- permitted, the one ON DELETE SET NULL performs when the account is already
-- gone, with every other column byte-identical. DELETE is refused always.
CREATE OR REPLACE FUNCTION refuse_status_event_change_except_erasure() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'this table is append-only';
  END IF;
  IF NEW."by" IS NULL AND OLD."by" IS NOT NULL AND actor_is_erased(OLD."by")
     AND ROW(NEW.id, NEW.role_id, NEW.from_status, NEW.to_status, NEW.basis,
             NEW.as_of, NEW.claim_id, NEW.at)
       IS NOT DISTINCT FROM
         ROW(OLD.id, OLD.role_id, OLD.from_status, OLD.to_status, OLD.basis,
             OLD.as_of, OLD.claim_id, OLD.at) THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'this table is append-only, except clearing the actor of a deleted account';
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER employment_status_events_append_only
  BEFORE UPDATE OR DELETE ON employment_status_events
  FOR EACH ROW EXECUTE FUNCTION refuse_status_event_change_except_erasure();


-- ---------------------------------------------------------------------------
-- 4. THE LOG IS WRITTEN BY THE DATABASE, NOT BY A CALLER
-- ---------------------------------------------------------------------------
-- Rev 268: the event log is "written by a trigger on the above". So any path
-- that changes a stored status is logged, including an admin UPDATE run by
-- hand, and no application change can quietly stop logging.
--
-- UPDATE OF names four columns because a re-verification changes
-- status_verified_at and nothing else, and that is an audit-worthy act. The
-- guard inside is on the VALUES: "UPDATE OF col" fires when the column appears
-- in the SET list whether or not the value changed, so a no-op write, which is
-- exactly what a replayed submission produces, must write no event.

CREATE OR REPLACE FUNCTION log_employment_status_change() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'UPDATE'
     AND ROW(NEW.employment_status, NEW.status_basis, NEW.status_as_of,
             NEW.status_verified_at, NEW.status_claim_id)
       IS NOT DISTINCT FROM
         ROW(OLD.employment_status, OLD.status_basis, OLD.status_as_of,
             OLD.status_verified_at, OLD.status_claim_id) THEN
    RETURN NULL;                                 -- nothing changed, nothing logged
  END IF;

  -- An INSERT at the default 'unknown' with no basis asserts nothing and is
  -- not an event. A role created already carrying a resolution is.
  IF TG_OP = 'INSERT' AND NEW.employment_status = 'unknown'
     AND NEW.status_basis IS NULL THEN
    RETURN NULL;
  END IF;

  INSERT INTO employment_status_events
        (role_id, from_status, to_status, basis, as_of, claim_id, "by")
  VALUES (NEW.id,
          CASE WHEN TG_OP = 'UPDATE' THEN OLD.employment_status END,
          NEW.employment_status, NEW.status_basis, NEW.status_as_of,
          NEW.status_claim_id, NEW.status_resolved_by);
  RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER career_history_status_logged
  AFTER INSERT OR UPDATE OF employment_status, status_basis, status_as_of, status_verified_at
  ON career_history
  FOR EACH ROW EXECUTE FUNCTION log_employment_status_change();


-- ---------------------------------------------------------------------------
-- 5. THE DISPUTE KIND
-- ---------------------------------------------------------------------------
-- A widening, so nothing that inserts today can start failing.
--
-- kind_blocks_publication IS DELIBERATELY NOT CHANGED. Rev 268: "this dispute
-- is about when somebody worked somewhere; it says nothing about whether a
-- rating may be published, and blocking publication on it would withhold
-- ratings over a question that has no bearing on them". blocks_publication is
-- a STORED generated column, so leaving the function alone means every
-- employment_status_dispute row is born non-blocking with no backfill.

ALTER TABLE role_conflicts DROP CONSTRAINT role_conflict_kind_known;
ALTER TABLE role_conflicts ADD CONSTRAINT role_conflict_kind_known CHECK (kind IN
  ('end_date_disagreement','ambiguous_resolution','unknown_period_duplicate',
   'overlapping_period','correction_candidate','late_edit_to_accepted',
   'duplicate_rating_on_merge','possible_duplicate',
   'cross_era_possible_duplicate','previously_rejected',
   'employment_status_dispute'));

-- A moderator's determination is not a merge, not a correction of one side,
-- and not a dismissal. It is an answer to "when did this employment run", so
-- it gets its own resolution value rather than being filed under the nearest
-- existing one.
ALTER TABLE role_conflicts DROP CONSTRAINT role_conflict_resolution_known;
ALTER TABLE role_conflicts ADD CONSTRAINT role_conflict_resolution_known CHECK
  (resolution IS NULL OR resolution IN
   ('merged','kept_separate','corrected','dismissed','superseded_the_legacy',
    'status_determined'));


-- ---------------------------------------------------------------------------
-- 6. THE RULES, AS FUNCTIONS, SO THERE IS ONE COPY OF EACH
-- ---------------------------------------------------------------------------

-- CONTRADICTION, and what is not one. Rev 251, verbatim: an ongoing assertion
-- as of T contradicts an ended assertion whose end date is EARLIER than T.
-- Differing dates alone, differing checkbox answers alone, and two
-- contributors who overlapped different years are NOT contradictions.
--
--   A left 2021 saying still employed, B says it ended 2024  -> COMPATIBLE
--   A left Jan 2025 saying still employed, B says Dec 2024   -> CONTRADICTION
--
-- IMMUTABLE: it is a function of its arguments and nothing else, which is what
-- lets a test call it directly with the design's own two examples.
CREATE OR REPLACE FUNCTION employment_status_contradicts(
         p_standing TEXT, p_standing_as_of TIMESTAMPTZ,
         p_new TEXT,      p_new_as_of      TIMESTAMPTZ)
RETURNS boolean AS $$
  SELECT CASE
    WHEN p_standing IS NULL OR p_new IS NULL                      THEN FALSE
    WHEN p_standing_as_of IS NULL OR p_new_as_of IS NULL          THEN FALSE
    WHEN p_standing = 'ongoing' AND p_new = 'ended'
         AND p_new_as_of      < p_standing_as_of                  THEN TRUE
    WHEN p_standing = 'ended'   AND p_new = 'ongoing'
         AND p_standing_as_of  < p_new_as_of                      THEN TRUE
    ELSE FALSE
  END;
$$ LANGUAGE sql IMMUTABLE;

-- STORED versus PRESENTED. Freshness is applied AT READ TIME and nothing
-- rewrites a row on a timer: a sweep flipping ongoing to unknown would destroy
-- the record of what was once established and make the event log lie.
--
-- STABLE, not IMMUTABLE, because of now(). That is also why it can never feed
-- a generated column, which is exactly the property wanted.
--
-- The window is a PARAMETER, not a literal, so the one named constant lives in
-- the application beside COOLDOWN_DAYS and a test can prove the boundary
-- without waiting eighteen months.
CREATE OR REPLACE FUNCTION employment_status_presented(
         p_status TEXT, p_as_of TIMESTAMPTZ, p_freshness_months INT)
RETURNS TEXT AS $$
  SELECT CASE
    -- 'ended' NEVER expires: a completed employment stays completed, and
    -- ageing it would turn known history into ignorance.
    WHEN p_status = 'ended'   THEN 'former'
    WHEN p_status = 'ongoing' AND p_as_of IS NOT NULL
         AND p_as_of > now() - make_interval(months => p_freshness_months) THEN 'current'
    ELSE 'unknown'
  END;
$$ LANGUAGE sql STABLE;

-- THE TILE'S LABEL, per (manager, company), which is what a reader sees.
--
--   Current  any record presents current
--   Former   at least one record, every one former, none unknown
--   Unknown  anything else
--
-- ACCEPTED ONLY, which resolves a contradiction between two sections of the
-- design in favour of the later one: the seven-cases section says to exclude
-- 'superseded' and 'rejected', which would leave 'proposed' in, while the
-- workforce-evidence table says a proposed record carries no status at all.
-- Flagged for approval in the proposal. One unknown record is enough to
-- prevent Former, because an episode we know nothing about might still be
-- running.
CREATE OR REPLACE FUNCTION employment_label(
         p_manager_id BIGINT, p_company_id BIGINT, p_freshness_months INT)
RETURNS TEXT AS $$
  WITH presented AS (
    SELECT employment_status_presented(ch.employment_status, ch.status_as_of,
                                       p_freshness_months) AS label
      FROM career_history ch
     WHERE ch.manager_id = p_manager_id
       AND ch.company_id = p_company_id
       AND ch.state = 'accepted')
  SELECT CASE
    WHEN EXISTS (SELECT 1 FROM presented WHERE label = 'current')  THEN 'current'
    WHEN EXISTS (SELECT 1 FROM presented)
         AND NOT EXISTS (SELECT 1 FROM presented WHERE label <> 'former') THEN 'former'
    ELSE 'unknown'
  END;
$$ LANGUAGE sql STABLE;
