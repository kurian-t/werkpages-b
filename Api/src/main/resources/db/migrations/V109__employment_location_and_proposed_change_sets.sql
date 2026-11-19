-- ─────────────────────────────────────────────────────────────────────────────
-- V109  PER-EMPLOYMENT LOCATION, AND A PROPOSED CHANGE SET THAT CAN CARRY A
--       WHOLE CAREER RATHER THAN ONE CURRENT POSITION
--
-- Approved 2026-11-17 for the career-history editor, which replaces the
-- "Edit Manager Details" form. That form could only ever express ONE position,
-- because `manager_edits` is one flat row of `new_title` / `new_company` /
-- `new_start_date`. A contributor who wanted to end a role and record its
-- successor had to submit, leave, and come back; a contributor who wanted to
-- fix a historical title had nowhere to say which employment they meant.
--
-- Four changes, one migration, because they are one product change and a single
-- transaction is one recovery decision instead of four:
--
--   1. career_history declared location  - each employment carries its OWN
--                                          place, in the same shape `reviews`
--                                          and `managers` already use.
--   2. manager_edits.new_name            - a name correction can be proposed at
--                                          all, which it could not before.
--   3. manager_edit_employments          - the per-position change set, each row
--                                          naming the employment it changes by
--                                          career_history.id.
--   4. role_corrections.field + location - so a location correction can be
--                                          recorded by the audit trail that
--                                          already records title and dates.
--
-- NOTHING IS BACKFILLED. See section 1.
--
-- RMM COMPATIBILITY (CLAUDE.md section 10): every column added here is nullable,
-- the one new table is new, and the two CHECK constraints are WIDENED, never
-- narrowed. RMM writes to career_history that omit these columns keep working
-- unchanged, and RMM is not required to know this table exists. No NOT NULL
-- without a default, no type change, no rename, no drop, no destructive update.
--
-- Idempotent throughout: every statement is IF NOT EXISTS or guarded, so a
-- re-run after a partial failure changes nothing.
-- ─────────────────────────────────────────────────────────────────────────────


-- ── 1. EACH EMPLOYMENT CARRIES ITS OWN LOCATION ─────────────────────────────
--
-- WHY NOT THE MANAGER'S LOCATION.
--
-- `managers` already has declared_country / declared_state / declared_city /
-- declared_precision / company_location_id / declared_source / declared_text,
-- and until now the edit form's location control wrote THOSE. So editing a role
-- somebody held in Toronto in 2014 moved the manager's present-day location to
-- Toronto, and there was no way to say where a historical role was held without
-- lying about where the person is now.
--
-- The columns below are the same eight, in the same types, with the same two
-- CHECK constraints `reviews` uses. Same shape on purpose: DeclaredLocation,
-- DeclaredLocationResolver and the LocationField component all already speak it,
-- so an employment location needs no new model on either side of the wire.
--
-- NO BACKFILL, DELIBERATELY.
--
-- An employment whose location nobody has stated has an UNKNOWN location, and
-- unknown is a real answer that the product is required to keep. Copying the
-- manager's current location onto historical rows would manufacture a confirmed
-- place for every role ever held, which is exactly the inference the declared /
-- observed split exists to prevent. Every column below starts NULL on every
-- existing row and stays NULL until somebody says otherwise.

ALTER TABLE career_history ADD COLUMN IF NOT EXISTS declared_country    VARCHAR(100);
ALTER TABLE career_history ADD COLUMN IF NOT EXISTS declared_state      VARCHAR(100);
ALTER TABLE career_history ADD COLUMN IF NOT EXISTS declared_city       VARCHAR(100);
ALTER TABLE career_history ADD COLUMN IF NOT EXISTS declared_precision  TEXT;
ALTER TABLE career_history ADD COLUMN IF NOT EXISTS company_location_id BIGINT;
ALTER TABLE career_history ADD COLUMN IF NOT EXISTS location_source     TEXT;
ALTER TABLE career_history ADD COLUMN IF NOT EXISTS declared_source     TEXT;
ALTER TABLE career_history ADD COLUMN IF NOT EXISTS declared_text       TEXT;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'career_history_company_location_id_fkey') THEN
    ALTER TABLE career_history
      ADD CONSTRAINT career_history_company_location_id_fkey
      FOREIGN KEY (company_location_id) REFERENCES company_locations(id);
  END IF;
END $$;

-- The same two guards `reviews` and `managers` carry. A manual answer is the one
-- a human typed, and it is worthless read back as a structured place it never
-- was, so it must keep its verbatim text.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'career_history_declared_source_valid') THEN
    ALTER TABLE career_history
      ADD CONSTRAINT career_history_declared_source_valid
      CHECK (declared_source IS NULL OR declared_source IN ('corpus', 'manual'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'career_history_manual_has_text') THEN
    ALTER TABLE career_history
      ADD CONSTRAINT career_history_manual_has_text
      CHECK (declared_source <> 'manual' OR btrim(COALESCE(declared_text, '')) <> '');
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS career_history_company_location
  ON career_history (company_location_id)
  WHERE company_location_id IS NOT NULL;


-- ── 2. A NAME CORRECTION CAN BE PROPOSED ────────────────────────────────────
--
-- `manager_edits` could propose a new company, title, status, country, linkedin
-- url and dates, but NOT a new name. A misspelled manager name was therefore
-- uncorrectable through the public form, which is why the editor is required to
-- offer it. Nullable, so every existing pending proposal stays valid and means
-- exactly what it meant before: no name change requested.

ALTER TABLE manager_edits ADD COLUMN IF NOT EXISTS new_name TEXT;


-- ── 3. THE CHANGE SET ───────────────────────────────────────────────────────
--
-- WHY A CHILD TABLE RATHER THAN MORE COLUMNS ON manager_edits.
--
-- The contribution is one coherent submission - one row in `manager_edits`,
-- carrying one explanation, one proposer, one pending/approved/rejected state
-- and one moderator decision. What it could not carry is more than one
-- position, and a career is a list. Widening the parent row cannot fix that: a
-- flat row can hold "the new title" but not "employment 41's new title, and
-- employment 52 has ended, and here are two roles nobody recorded".
--
-- So the parent stays the envelope and this table is its contents, one row per
-- position the contributor touched. Approval is still one decision over one
-- `manager_edits` row, which is what keeps the submission coherent.
--
-- THIS IS NOT A SECOND MODERATION SYSTEM. Nothing here is a queue, a state
-- machine or an approval record. The pending/approved/rejected state, the
-- reviewer and the review timestamp all remain on `manager_edits`, and applying
-- an approved row goes through ModerationService's existing per-employment
-- paths. These rows are the proposal's payload, and nothing else.
--
-- WHY role_id, AND WHY IT IS A REAL FOREIGN KEY.
--
-- A change to an existing employment must name THAT employment. Identifying it
-- by company name, or by company plus title, cannot distinguish two spells at
-- the same employer in the same role, and quietly rewrites the wrong one when it
-- guesses. career_history.id is the canonical employment identifier and this
-- column is a foreign key to it, so a proposal naming an employment that has
-- since been merged away cannot be stored at all.

CREATE TABLE IF NOT EXISTS manager_edit_employments (
  id                      BIGSERIAL PRIMARY KEY,

  edit_id                 UUID   NOT NULL REFERENCES manager_edits(id) ON DELETE CASCADE,

  -- What the contributor is doing to this position, which is the distinction the
  -- whole editor is built around:
  --
  --   correct    - this employment's record is wrong. Same employment, fixed
  --                values. Ratings and claims stay exactly where they are.
  --   add        - an employment nobody had recorded, historical or current.
  --                A DISTINCT employment: it never terminates another.
  --   supersede  - this employment names the wrong EMPLOYER. Not a correction,
  --                because the employer is what ratings are attributed through,
  --                and not an addition, because the person did not change jobs.
  --                On approval the named row becomes state='superseded' pointing
  --                at a replacement, inside one transaction.
  intent                  TEXT   NOT NULL,

  -- The employment being changed. NULL only for `add`.
  role_id                 BIGINT REFERENCES career_history(id) ON DELETE CASCADE,

  -- WHICH fields this row actually proposes, so "not mentioned" is distinguished
  -- from "deliberately cleared". Without it, a NULL new_end_date is ambiguous
  -- between "leave the end date alone" and "this role has no end date after
  -- all", and those are opposite instructions. The Correction patch record on
  -- the Java side carries the same distinction as its *Given booleans.
  changed_fields          TEXT[] NOT NULL,

  new_title               TEXT,
  new_company_id          BIGINT REFERENCES companies(id) ON DELETE SET NULL,
  -- A company the contributor named that is not in `companies` yet, kept as text
  -- exactly as `manager_edits.new_company` is, so moderation can resolve it
  -- rather than the public form creating an employer on an unapproved proposal.
  new_company_name        TEXT,
  new_start_date          TIMESTAMPTZ,
  new_end_date            TIMESTAMPTZ,
  new_period_known        BOOLEAN,

  -- 'ongoing' | 'ended' | 'unknown' only. `disputed` is a moderation outcome
  -- reached when accounts contradict each other, never something a contributor
  -- may assert about somebody else's job, so it is refused here rather than
  -- merely hidden by the form.
  new_employment_status   TEXT,

  -- The same eight declared-location fields, minus location_source, which is
  -- resolved by the server and never proposed.
  new_declared_country    VARCHAR(100),
  new_declared_state      VARCHAR(100),
  new_declared_city       VARCHAR(100),
  new_declared_precision  TEXT,
  new_company_location_id BIGINT REFERENCES company_locations(id),
  new_declared_source     TEXT,
  new_declared_text       TEXT,

  -- ── WHAT THIS EMPLOYMENT SAID WHEN THE PROPOSAL WAS WRITTEN ──────────────
  --
  -- The proposal has to carry the values it was written against, not just the
  -- values it proposes.
  --
  -- Comparing the proposal to career_history AT APPROVAL TIME cannot detect a
  -- change made in between: if a moderator fixed this title an hour after the
  -- contributor submitted, the proposal still looks like a clean edit of the
  -- title it was written against, and approving it reverts the moderator's work
  -- with nothing anywhere recording that it happened. The contributor is not
  -- wrong and the moderator is not wrong; the proposal is simply stale, and
  -- staleness is only visible against a snapshot.
  --
  -- So these are the employment as it stood at submission. Approval compares
  -- them with the row as it stands now, for the fields in changed_fields only,
  -- and surfaces a divergence to the moderator instead of silently overwriting
  -- the newer value. NULL throughout for `add`, which was written against
  -- nothing.
  was_title               TEXT,
  was_company_id          BIGINT,
  was_start_date          TIMESTAMPTZ,
  was_end_date            TIMESTAMPTZ,
  was_period_known        BOOLEAN,
  was_employment_status   TEXT,
  was_declared_country    VARCHAR(100),
  was_declared_state      VARCHAR(100),
  was_declared_city       VARCHAR(100),
  was_declared_precision  TEXT,
  was_company_location_id BIGINT,
  -- The employment's lifecycle state and last-touched time when the proposal was
  -- written. A row that has since been superseded, rejected or merged away is
  -- not merely stale in one field: the thing the proposal is about has moved,
  -- and approving a field edit on it would be meaningless.
  was_state               TEXT,
  was_updated_at          TIMESTAMPTZ,

  -- Display order within the contribution, so the moderator reads the change set
  -- in the order the contributor built it.
  position                INTEGER NOT NULL DEFAULT 0,

  created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT edit_employment_intent_known
    CHECK (intent IN ('correct', 'add', 'supersede')),

  -- An addition targets nothing; a correction and a supersession must target
  -- exactly one existing employment.
  CONSTRAINT edit_employment_targets_match_intent
    CHECK ((intent = 'add') = (role_id IS NULL)),

  -- A supersession exists to change the employer, so it has to name one.
  CONSTRAINT edit_employment_supersede_names_a_company
    CHECK (intent <> 'supersede'
           OR new_company_id IS NOT NULL
           OR btrim(COALESCE(new_company_name, '')) <> ''),

  -- cardinality(), NOT array_length().
  --
  -- array_length('{}', 1) is NULL rather than 0, so `array_length(...) >= 1`
  -- evaluates to NULL for the empty array, and a CHECK passes on NULL. That
  -- wrote a change-set row proposing nothing, which then occupied this
  -- employment's slot in edit_employment_one_row_per_role and blocked the real
  -- correction behind it. cardinality() returns 0 for '{}' and the check bites.
  CONSTRAINT edit_employment_changes_something
    CHECK (cardinality(changed_fields) >= 1),

  -- changed_fields drives what the approval path applies, so an unrecognised
  -- name would be stored, shown to a moderator as part of the change set, and
  -- then silently applied to nothing. A NULL element does the same, and would
  -- make the subset test below return NULL rather than false.
  CONSTRAINT edit_employment_fields_known
    CHECK (array_position(changed_fields, NULL) IS NULL
           AND changed_fields <@ ARRAY['title', 'company', 'location',
                                       'start_date', 'end_date', 'period_known',
                                       'employment_status']::text[]),

  CONSTRAINT edit_employment_status_known
    CHECK (new_employment_status IS NULL
           OR new_employment_status IN ('ongoing', 'ended', 'unknown')),

  -- end before start is refused here, not only in the form, so a proposal that
  -- reached the API by another route cannot be stored.
  CONSTRAINT edit_employment_period_ordered
    CHECK (new_end_date IS NULL OR new_start_date IS NULL OR new_end_date >= new_start_date),

  CONSTRAINT edit_employment_declared_source_valid
    CHECK (new_declared_source IS NULL OR new_declared_source IN ('corpus', 'manual')),

  CONSTRAINT edit_employment_manual_has_text
    CHECK (new_declared_source <> 'manual'
           OR btrim(COALESCE(new_declared_text, '')) <> '')
);

-- One contribution may not say two different things about the same employment.
-- Two rows for one role_id is a change set that contradicts itself, and the
-- moderator would have no way to tell which half was meant.
CREATE UNIQUE INDEX IF NOT EXISTS edit_employment_one_row_per_role
  ON manager_edit_employments (edit_id, role_id)
  WHERE role_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS edit_employment_by_edit
  ON manager_edit_employments (edit_id, position, id);

CREATE INDEX IF NOT EXISTS edit_employment_by_role
  ON manager_edit_employments (role_id)
  WHERE role_id IS NOT NULL;


-- ── 4. LOCATION IS A CORRECTABLE FIELD ──────────────────────────────────────
--
-- `role_corrections` is the append-only record of what was corrected on an
-- employment, one row per field, and its CHECK admitted exactly title,
-- start_date, end_date and period_known. A location correction had nowhere to be
-- recorded, so approving one would have changed the row while leaving the history
-- silent about it.
--
-- WIDENED, NOT REDEFINED: the four existing values stay, 'location' joins them.
-- Every existing row still satisfies it.
--
-- `company` is deliberately still ABSENT. A company change is a supersession,
-- recorded by career_history.superseded_by on the row it replaces, because
-- rewriting the employer in place would silently re-attribute every rating hung
-- off that employment. `employment_status` is deliberately absent too: its
-- transitions are recorded by `employment_status_events`, which carries the
-- basis and as-of date that a one-line field correction cannot.

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'role_correction_field_known') THEN
    ALTER TABLE role_corrections DROP CONSTRAINT role_correction_field_known;
  END IF;
  ALTER TABLE role_corrections
    ADD CONSTRAINT role_correction_field_known
    CHECK (field IN ('title', 'start_date', 'end_date', 'period_known', 'location'));
END $$;


-- ── 5. WHICH SHAPE A CONTRIBUTION IS ────────────────────────────────────────
--
-- WHY NOT JUST ASK WHETHER A CHANGE SET EXISTS.
--
-- The obvious discriminator is "does this edit have manager_edit_employments
-- rows", and it is wrong for a real case: a contributor who corrects only the
-- manager's NAME touches no employment, so their change set is legitimately
-- empty. Routed by the presence of rows, that submission would fall through to
-- the legacy path, which reads new_title / new_company / new_start_date - all
-- null - and would approve a contribution by applying nothing at all. The name
-- correction would be marked approved and silently dropped.
--
-- So the shape is declared, not inferred. A row written by the career editor
-- says so, whatever it happens to contain.

ALTER TABLE manager_edits
  ADD COLUMN IF NOT EXISTS change_format TEXT NOT NULL DEFAULT 'legacy';

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'manager_edits_change_format_known') THEN
    ALTER TABLE manager_edits
      ADD CONSTRAINT manager_edits_change_format_known
      CHECK (change_format IN ('legacy', 'career_editor'));
  END IF;
END $$;

-- Every edit that exists today was written by the old form, and the default
-- above already said so for new rows. This states it for the existing ones
-- too, which the default does not touch.
UPDATE manager_edits SET change_format = 'legacy' WHERE change_format IS NULL;

-- ── ONE EXPLANATION PER SUBMISSION ──────────────────────────────────────────
--
-- role_corrections.reason is NOT NULL with a non-blank CHECK, so every field
-- correction recorded at approval needs a reason. Asking the contributor for one
-- per field would mean typing the same sentence five times to fix five things,
-- so the editor asks once, during Review Changes, and that explanation is reused
-- for each correction the submission produces.
--
-- It lives on the parent row because it describes the contribution, not any one
-- position.

ALTER TABLE manager_edits ADD COLUMN IF NOT EXISTS explanation TEXT;

-- A career-editor submission always carries one; a legacy row never did and is
-- left alone. Enforced here as well as in the service because the explanation is
-- what the audit trail will quote, and a blank one cannot be fixed afterwards.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'manager_edits_career_editor_explains') THEN
    ALTER TABLE manager_edits
      ADD CONSTRAINT manager_edits_career_editor_explains
      CHECK (change_format <> 'career_editor'
             OR btrim(COALESCE(explanation, '')) <> '');
  END IF;
END $$;


-- A correction or supersession must carry the snapshot it was written against,
-- so no proposal can skip the staleness comparison by simply omitting it.
-- Additions carry none, having been written against nothing.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'edit_employment_targeted_rows_snapshot') THEN
    ALTER TABLE manager_edit_employments
      ADD CONSTRAINT edit_employment_targeted_rows_snapshot
      CHECK ((intent = 'add') = (was_state IS NULL));
  END IF;
END $$;


-- ── 6. AN OLD BACKEND MUST NOT APPROVE A NEW-FORMAT CONTRIBUTION ────────────
--
-- THE DEPLOYMENT HAZARD THIS CLOSES.
--
-- WerkPages and RateMyManagers are two backends on one database and they deploy
-- sequentially, so for a while one of them is running code written before the
-- career editor existed. Both can approve edit requests.
--
-- An old backend handed a career-editor contribution does the most dangerous
-- possible thing: it reads the parent row's new_title / new_company / dates,
-- finds them null, applies nothing, sets status = 'approved', and tells the
-- moderator it worked. The change set is still sitting in
-- manager_edit_employments, now attached to a contribution that can never be
-- approved again because it is no longer pending. The contributor's work is
-- gone, nothing failed, and nobody finds out.
--
-- Application code cannot defend against this, because the code that needs
-- fixing is the code that is already deployed. The database can, because both
-- backends share it.
--
-- HOW IT WORKS. New code announces itself inside its transaction with
--   SELECT set_config('rmm.career_edit_apply', 'on', true)
-- exactly as ModerationService already announces an admin decision through
-- 'rmm.resolution_reason'. Old code does not set it, because it does not know
-- it exists, so its approval of a career-editor row is REFUSED. A refusal leaves
-- the contribution pending and raises an error somebody will see, which is the
-- correct failure: a visible one that preserves the work.
--
-- Legacy contributions are untouched by this - they approve exactly as they do
-- today, from either backend, deployed or not.

CREATE OR REPLACE FUNCTION refuse_stale_format_approval() RETURNS trigger AS $$
BEGIN
  IF NEW.change_format = 'career_editor'
     AND NEW.status IS DISTINCT FROM OLD.status
     AND NEW.status IN ('approved', 'rejected')
     AND COALESCE(current_setting('rmm.career_edit_apply', true), '') <> 'on' THEN
    RAISE EXCEPTION
      'This edit request was submitted through the career history editor and '
      'carries a change set of % position(s). The running backend does not '
      'understand that format, and approving it here would apply none of it. '
      'Deploy the current backend before deciding this request.',
      (SELECT count(*) FROM manager_edit_employments WHERE edit_id = NEW.id)
      USING ERRCODE = 'raise_exception';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS manager_edits_format_guard ON manager_edits;
CREATE TRIGGER manager_edits_format_guard
  BEFORE UPDATE ON manager_edits
  FOR EACH ROW EXECUTE FUNCTION refuse_stale_format_approval();
