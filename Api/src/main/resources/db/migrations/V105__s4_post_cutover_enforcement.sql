-- ============================================================================
-- V105: S4 post-cutover enforcement, INERT UNTIL EXPLICITLY ACTIVATED
--
-- Rev 268 calls S4 "the point of no return": the moment a rating may no longer
-- be submitted without an employment to attach it to. Everything needed for
-- that is created here, and NOTHING is enforced, because activation is a
-- product decision that has not been made.
--
-- Applying this migration changes no behaviour whatsoever. The flag defaults
-- false, the trigger returns immediately while it is false, and every existing
-- rating is untouched. That is deliberate and it is testable in both states.
--
-- ── WHY TRIGGERS AND NOT CHECK CONSTRAINTS ──────────────────────────────────
--
-- The obvious shape is a CHECK that consults the flag:
--
--     CHECK (NOT s4_enforced() OR <condition>)
--
-- It is wrong, and the reason is not stylistic. A CHECK constraint whose
-- expression reads another table is unsound in PostgreSQL: the constraint is
-- not re-evaluated when that other table changes, and - the part that bites -
-- `pg_dump` followed by a restore RE-VALIDATES check constraints against every
-- restored row. Restore a backup while the flag happens to be on and the
-- restore fails on legacy data that was always meant to be exempt. A backup
-- that cannot be restored is not a backup.
--
-- A BEFORE trigger has none of that. It is consulted only on write, it may read
-- whatever it likes, and a dump/restore does not fire it.
--
-- ── WHAT S4 ACTUALLY CHANGES, which is less than it first appears ───────────
--
-- V97 already carries the constraints that do the work:
--
--     reviews_role_present                 role_required = FALSE OR claim_id IS NOT NULL
--     reviews_role_required_follows_basis  role_required = (role_basis = 'claimed')
--
-- So a claimed rating already must have a claim, and a legacy one already must
-- not pretend to. What S4 adds is a refusal to accept NEW legacy-shaped
-- ratings at all - the old write path - with one exemption for work that was
-- captured before the cutover and published afterwards.
-- ============================================================================

-- ── 1. the switch ───────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS platform_settings (
    /*
      One row, enforced. A settings table that can hold two rows is a settings
      table that will, and then nothing agrees about which is in force.
    */
    only_row              BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (only_row),

    s4_enforcement_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    s4_activated_at        TIMESTAMPTZ,
    s4_activated_by        UUID        REFERENCES users(id) ON DELETE SET NULL,

    -- Activation must say who and when, because "the point of no return" is a
    -- thing somebody should be accountable for having passed.
    CONSTRAINT s4_activation_is_recorded CHECK (
        s4_enforcement_enabled = FALSE OR s4_activated_at IS NOT NULL)
);

INSERT INTO platform_settings (only_row) VALUES (TRUE) ON CONFLICT DO NOTHING;

/**
 * Whether S4 enforcement is in force. FALSE unless somebody turned it on.
 *
 * STABLE, not IMMUTABLE: it reads a table, and declaring otherwise would let
 * the planner cache a stale answer across the statement that flips it.
 */
CREATE OR REPLACE FUNCTION s4_enforced() RETURNS BOOLEAN
LANGUAGE sql STABLE AS $$
    SELECT COALESCE((SELECT s4_enforcement_enabled FROM platform_settings), FALSE);
$$;

-- ── 2. declared provenance for a rating that may omit a role ────────────────

ALTER TABLE reviews ADD COLUMN IF NOT EXISTS role_exemption TEXT;

/*
  The exemption is DECLARED by the writer and VERIFIED against a frozen set.

  The design's fourth pass settled this after two earlier attempts failed. The
  first tested the rating's own created_at against the cutover timestamp, which
  is wrong because a draft captured before the cutover and published after it
  gets now() - so a valid old contribution was refused by the constraint meant
  to protect it.

  An application therefore states WHY a rating may omit a role, and the database
  checks that claim rather than believing it.
*/
ALTER TABLE reviews DROP CONSTRAINT IF EXISTS reviews_role_exemption_known;
ALTER TABLE reviews ADD CONSTRAINT reviews_role_exemption_known
    CHECK (role_exemption IS NULL OR role_exemption IN ('pre_cutover_draft'));

-- Only a rating with no claim has anything to be exempt from.
ALTER TABLE reviews DROP CONSTRAINT IF EXISTS reviews_exemption_only_without_a_claim;
ALTER TABLE reviews ADD CONSTRAINT reviews_exemption_only_without_a_claim
    CHECK (role_exemption IS NULL OR claim_id IS NULL);

-- ── 3. the set that cannot grow ─────────────────────────────────────────────

/*
  Populated ONCE at activation, from the drafts that exist at that moment, and
  never again. That is what makes the exemption close by construction: no draft
  created after the cutover can ever be in it, so the exemption cannot become a
  permanent way around the rule.
*/
CREATE TABLE IF NOT EXISTS pre_cutover_drafts (
    /*
      UUID, matching reviews.draft_token and captured_drafts.draft_token.

      Declared TEXT in the first draft of this migration, which type-checked
      fine and failed at the only moment that mattered: a genuine pre-cutover
      draft could not publish, because comparing reviews.draft_token (uuid)
      against a text column raised 22P02 before the exemption was ever
      consulted. The enforcement looked correct and the ESCAPE HATCH was
      broken - the failure mode that strands real contributions.

      Caught by testing the enabled state, which is the whole reason for
      testing it rather than reasoning about it.
    */
    draft_token UUID PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION refuse_pre_cutover_drafts_growth() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    /*
      Refused once S4 is live. Before activation the table is still being
      assembled - activation itself fills it - so the guard only closes after
      the switch is thrown.

      Without this the exemption is unbounded: anybody able to insert a token
      here could publish a claimless rating for ever, which is precisely the
      hole S4 exists to shut.
    */
    IF s4_enforced() THEN
        RAISE EXCEPTION
            'pre_cutover_drafts is frozen: it is populated once at S4 activation and never '
            'added to, because an exemption that can grow is not an exemption';
    END IF;
    RETURN NEW;
END; $$;

DROP TRIGGER IF EXISTS pre_cutover_drafts_are_frozen ON pre_cutover_drafts;
CREATE TRIGGER pre_cutover_drafts_are_frozen
    BEFORE INSERT OR UPDATE ON pre_cutover_drafts
    FOR EACH ROW EXECUTE FUNCTION refuse_pre_cutover_drafts_growth();

-- ── 4. the enforcement itself ───────────────────────────────────────────────

/**
 * Refuses a NEW claimless rating once S4 is in force.
 *
 * Reads as a list of things that are still allowed, because that is the safer
 * way round: anything not explicitly permitted is refused, rather than a list
 * of refusals that a new shape of rating can slip past.
 *
 * It fires on INSERT ONLY. An UPDATE to an existing legacy rating - an edit, a
 * withdrawal, a merge moving it, an attribution correction - must keep working
 * for ever. S4 is about what may be CREATED after the cutover; the history is
 * not retrospectively illegal, and a trigger that refused updates would make
 * every legacy rating unmaintainable the moment the flag flipped.
 */
CREATE OR REPLACE FUNCTION enforce_s4_role_required() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT s4_enforced() THEN
        RETURN NEW;                      -- inert, which is the default state
    END IF;

    -- A rating that names its employment is the ordinary post-cutover case.
    IF NEW.claim_id IS NOT NULL THEN
        RETURN NEW;
    END IF;

    -- A withdrawn claim leaves a rating claimless and it stays publishable; the
    -- employment it described did not stop having happened.
    IF NEW.role_basis = 'withdrawn' THEN
        RETURN NEW;
    END IF;

    -- The one exemption, and it is verified rather than believed.
    IF NEW.role_exemption = 'pre_cutover_draft' THEN
        IF NEW.draft_token IS NULL THEN
            RAISE EXCEPTION
                'a rating claiming the pre_cutover_draft exemption must carry the draft_token '
                'that proves it';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pre_cutover_drafts p WHERE p.draft_token = NEW.draft_token) THEN
            RAISE EXCEPTION
                'draft_token % is not in pre_cutover_drafts, so the pre_cutover_draft exemption '
                'does not apply to it. The set is frozen at activation; a draft created after '
                'the cutover cannot be exempt', NEW.draft_token;
        END IF;
        RETURN NEW;
    END IF;

    /*
      Everything else is the old write path, and this is the deliberate refusal
      the design asks for: a named, explicable error, not a constraint violation
      surfacing as a 500.

      SQLSTATE 'S4010' is a custom class, chosen so the API layer can recognise
      it and answer 410 Gone with an explanatory body rather than guessing from
      a message string.
    */
    RAISE EXCEPTION
        'this rating has no employment to attach to, and submissions without one are no longer '
        'accepted (S4). The submitting client is using a retired path.'
        USING ERRCODE = 'S4010';
END; $$;

DROP TRIGGER IF EXISTS reviews_enforce_s4 ON reviews;
CREATE TRIGGER reviews_enforce_s4
    BEFORE INSERT ON reviews
    FOR EACH ROW EXECUTE FUNCTION enforce_s4_role_required();

-- ── 5. activation and deactivation, as functions rather than loose SQL ──────

/**
 * Throws the switch, and fills the frozen exemption set in the SAME transaction.
 *
 * One function because the two halves cannot be separated safely. Flipping the
 * flag first would refuse every in-flight pre-cutover draft until the set was
 * filled; filling the set first would briefly allow anything in it while the
 * rule was still off. Together, neither window exists.
 *
 * NOT CALLED BY THIS MIGRATION. Activation is a product decision and requires
 * explicit authorization; this only makes it a single auditable step when it
 * comes.
 */
CREATE OR REPLACE FUNCTION activate_s4_enforcement(p_actor UUID)
RETURNS TABLE (drafts_exempted BIGINT)
LANGUAGE plpgsql AS $$
DECLARE
    v_count BIGINT;
BEGIN
    IF s4_enforced() THEN
        RAISE EXCEPTION 'S4 enforcement is already active since %',
            (SELECT s4_activated_at FROM platform_settings);
    END IF;

    /*
      Every draft that exists right now and could still be published without a
      claim. Captured drafts carry a token; the contribution drafts introduced
      later do too.
    */
    INSERT INTO pre_cutover_drafts (draft_token)
    SELECT DISTINCT draft_token FROM captured_drafts
     WHERE draft_token IS NOT NULL
       AND state <> 'published'
    ON CONFLICT DO NOTHING;

    SELECT count(*) INTO v_count FROM pre_cutover_drafts;

    UPDATE platform_settings
       SET s4_enforcement_enabled = TRUE,
           s4_activated_at        = now(),
           s4_activated_by        = p_actor;

    RAISE NOTICE 'S4 enforcement ACTIVE. % pre-cutover drafts may still publish without a role.',
                 v_count;
    RETURN QUERY SELECT v_count;
END; $$;

/**
 * Turns it back off, which is possible on purpose.
 *
 * Rev 268 calls S4 the point of no return and it is, for the DATA: ratings
 * created under enforcement carry claims and nothing undoes that. The SWITCH is
 * a different thing, and a switch that cannot be thrown back is an outage with
 * no remedy. The frozen set is left alone so reactivating does not re-open it.
 */
CREATE OR REPLACE FUNCTION deactivate_s4_enforcement() RETURNS VOID
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE platform_settings
       SET s4_enforcement_enabled = FALSE,
           s4_activated_at        = NULL,
           s4_activated_by        = NULL;
    RAISE NOTICE 'S4 enforcement is OFF. pre_cutover_drafts keeps its % rows.',
                 (SELECT count(*) FROM pre_cutover_drafts);
END; $$;

COMMENT ON TABLE platform_settings IS
    'One row. Holds the S4 enforcement switch, which defaults OFF: applying V105 changes no '
    'behaviour at all. Activation is activate_s4_enforcement() and is a product decision.';

COMMENT ON FUNCTION enforce_s4_role_required IS
    'Refuses a NEW claimless rating once S4 is in force. INSERT only - every update to an '
    'existing legacy rating must keep working, because history is not retrospectively illegal.';
