-- ============================================================================
-- V104: an auditable way to correct an attribution that turns out to be wrong
--
-- V102 derives an employer for 16,286 legacy ratings from the work period the
-- contributor stated, matched against the manager's employment record. That is
-- good evidence. It is still an INFERENCE, and inferences are sometimes wrong:
-- an employment record gets corrected, a period turns out to have been typed
-- loosely, two companies turn out to have been the same one all along.
--
-- When that happens there must be a way to put it right that leaves a record.
-- Silently rewriting attribution is how a statistic stops being explainable:
-- somebody asks why a company's figure moved and nobody can say.
--
-- ── WHAT MAKES THIS POSSIBLE AT ALL ─────────────────────────────────────────
--
-- `attribution_basis = 'inferred'`, added by V102. Before that, every legacy
-- attribution was stamped 'submitted' - indistinguishable from an employer a
-- contributor actually named - so "re-derive the ones we worked out" was not a
-- query anybody could write. The provenance is what makes the set correctable.
--
-- ── WHAT THIS IS NOT ────────────────────────────────────────────────────────
--
-- Not a general edit log for ratings; V97 already has those. This records one
-- specific thing: a change to where a rating's employment is attributed, and
-- why. Narrow on purpose, because a table that records everything is a table
-- nobody reads.
-- ============================================================================

CREATE TABLE IF NOT EXISTS rating_attribution_corrections (
    id                  BIGSERIAL PRIMARY KEY,
    review_id           UUID        NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,

    -- Both sides of the change, so the row explains itself without needing the
    -- rating's current state - which will have moved on by the time anybody
    -- reads this.
    previous_basis      TEXT        NOT NULL,
    previous_company_id BIGINT      REFERENCES companies(id) ON DELETE SET NULL,
    new_basis           TEXT        NOT NULL,
    new_company_id      BIGINT      REFERENCES companies(id) ON DELETE SET NULL,

    /*
      Why. Required, and not from a fixed list.

      A correction without a reason is a mystery with a timestamp. The free text
      is deliberate: the reasons are the kind nobody can enumerate in advance
      ("the employment record said 2019 but their offer letter says 2020").
    */
    reason              TEXT        NOT NULL CHECK (btrim(reason) <> ''),

    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    /*
      Who. Nullable with the same erasure handling session revocation uses: an
      administrator may later delete their own account, and that must not either
      cascade away the audit trail or violate a NOT NULL.
    */
    corrected_by        UUID        REFERENCES users(id) ON DELETE SET NULL,
    actor_erased        BOOLEAN     NOT NULL DEFAULT FALSE,

    -- A correction made by a migration re-deriving the whole set has no human
    -- actor, and saying so is better than inventing one.
    corrected_by_system BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT correction_names_an_actor
        CHECK (corrected_by IS NOT NULL OR actor_erased OR corrected_by_system),

    -- The basis values V97 and V102 allow. Written out rather than referencing
    -- the reviews constraint, because this table records what the basis WAS at
    -- a past moment and must keep accepting a value the live schema later drops.
    CONSTRAINT correction_bases_known CHECK (
        previous_basis IN ('role','withdrawn_role','submitted','inferred','unresolved','none')
        AND new_basis  IN ('role','withdrawn_role','submitted','inferred','unresolved','none'))
);

CREATE INDEX IF NOT EXISTS attribution_corrections_by_review
    ON rating_attribution_corrections (review_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS attribution_corrections_recent
    ON rating_attribution_corrections (occurred_at DESC);

-- Same shape as V100's session_revocation_events: when the actor's account goes,
-- the row survives and says that it had one.
CREATE OR REPLACE FUNCTION attribution_correction_actor_erased() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.corrected_by IS NOT NULL AND NEW.corrected_by IS NULL THEN
        NEW.actor_erased := TRUE;
    END IF;
    RETURN NEW;
END; $$;

DROP TRIGGER IF EXISTS attribution_correction_actor_erasure ON rating_attribution_corrections;
CREATE TRIGGER attribution_correction_actor_erasure
    BEFORE UPDATE OF corrected_by ON rating_attribution_corrections
    FOR EACH ROW EXECUTE FUNCTION attribution_correction_actor_erased();

-- ── applying a correction ───────────────────────────────────────────────────

/**
 * Records and applies a correction, in one statement, or does neither.
 *
 * The recording and the change MUST be atomic. A correction applied without a
 * record is the silent rewrite this migration exists to prevent; a record
 * written without the change is a lie about what the data says. Doing both in
 * one function body, inside the caller's transaction, is what makes "audited"
 * true rather than aspirational.
 *
 * Refuses to touch a CLAIMED rating. Its employer comes from its claim, and
 * V97's reviews_role_basis_stores_nothing forbids it carrying a company of its
 * own - so a "correction" there is a claim change, which is a different
 * operation with different consequences.
 *
 * Returns TRUE when something changed, FALSE when the rating already said what
 * was asked for - so a retried correction is harmless and does not accumulate
 * identical audit rows.
 */
CREATE OR REPLACE FUNCTION correct_rating_attribution(
    p_review_id   UUID,
    p_new_basis   TEXT,
    p_new_company BIGINT,
    p_reason      TEXT,
    p_actor       UUID,
    p_by_system   BOOLEAN DEFAULT FALSE
) RETURNS BOOLEAN
LANGUAGE plpgsql AS $$
DECLARE
    v_old_basis   TEXT;
    v_old_company BIGINT;
    v_role_basis  TEXT;
BEGIN
    SELECT attribution_basis, attributed_company_id, role_basis
      INTO v_old_basis, v_old_company, v_role_basis
      FROM reviews WHERE id = p_review_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'no such rating: %', p_review_id;
    END IF;

    IF v_role_basis = 'claimed' THEN
        RAISE EXCEPTION 'rating % is claimed; its employer follows its claim and cannot be '
                        'corrected here', p_review_id;
    END IF;

    -- Nothing to do, and nothing to record.
    IF v_old_basis = p_new_basis
       AND v_old_company IS NOT DISTINCT FROM p_new_company THEN
        RETURN FALSE;
    END IF;

    INSERT INTO rating_attribution_corrections
        (review_id, previous_basis, previous_company_id, new_basis, new_company_id,
         reason, corrected_by, corrected_by_system)
    VALUES
        (p_review_id, v_old_basis, v_old_company, p_new_basis, p_new_company,
         p_reason, p_actor, p_by_system);

    UPDATE reviews
       SET attribution_basis     = p_new_basis,
           attributed_company_id = p_new_company
     WHERE id = p_review_id;

    -- The statement triggers V101 installed on `reviews` recompute the affected
    -- companies, so the projection follows a correction without the caller
    -- having to know the projection exists.
    RETURN TRUE;
END; $$;

COMMENT ON FUNCTION correct_rating_attribution IS
    'Records and applies a change to a rating''s employment attribution, atomically. '
    'The audit row and the change are written together or not at all, because a '
    'correction without a record is the silent rewrite V104 exists to prevent.';

COMMENT ON TABLE rating_attribution_corrections IS
    'Every change to where a rating''s employment is attributed, with a reason. '
    'Exists because V102 INFERS the employer for legacy ratings, and an inference '
    'can later be shown wrong by evidence the backfill did not have.';
