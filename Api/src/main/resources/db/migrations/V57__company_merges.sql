-- Phase 3: merging two companies that were always one.
--
-- Everything before this stopped NEW duplicates being created. This is the machinery for the ones
-- already in the database. It is the first destructive operation in the system, so the design is
-- built around being able to explain and undo what it did.
--
-- Three principles, each of which is a table below:
--
--   1. A merge records exactly which rows it moved, by id. Not a summary, not a JSON snapshot of
--      "how things looked" - the actual row ids, so an undo is a reverse UPDATE rather than an
--      archaeological reconstruction.
--   2. The source company is retired, never deleted. It stops being writable and stops appearing,
--      but it still exists to be pointed at.
--   3. Its URL keeps working forever. People share links to company pages; a merge must not turn
--      those into 404s.

-- ── The merge itself ─────────────────────────────────────────────────────────────────────────

CREATE TABLE company_merges (
    id                UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    source_company_id BIGINT      NOT NULL REFERENCES companies(id),
    target_company_id BIGINT      NOT NULL REFERENCES companies(id),
    merged_by         UUID        NOT NULL REFERENCES users(id),
    merged_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 'completed' the moment the transaction commits; 'reverted' once an undo has run. There is no
    -- 'pending': a merge either happened inside its transaction or it did not happen at all.
    status            TEXT        NOT NULL DEFAULT 'completed'
                      CHECK (status IN ('completed', 'reverted')),
    -- What the source looked like, for the admin screen and for explaining a merge months later.
    -- Deliberately NOT the mechanism for undo - that is company_merge_records.
    source_snapshot   JSONB       NOT NULL DEFAULT '{}'::JSONB,

    -- A company cannot be merged into itself. Cheap to check here, disastrous to discover later.
    CONSTRAINT company_merges_distinct CHECK (source_company_id <> target_company_id)
);

CREATE INDEX company_merges_source ON company_merges (source_company_id);
CREATE INDEX company_merges_target ON company_merges (target_company_id);

-- ── The manifest ─────────────────────────────────────────────────────────────────────────────
--
-- One row per record the merge touched. This is what makes undo real: to reverse merge #57 you
-- select its records and put each one back where it came from, with no guessing about which of the
-- target's rows used to belong to the source.
CREATE TABLE company_merge_records (
    id             BIGSERIAL PRIMARY KEY,
    merge_id       UUID      NOT NULL REFERENCES company_merges(id) ON DELETE CASCADE,
    -- 'manager' | 'career_history' | 'interview_review' | 'interview_review_deletion' | 'manager_edit'
    entity_type    TEXT      NOT NULL,
    -- Text because the tables disagree: managers use BIGINT, interview reviews use UUID.
    record_id      TEXT      NOT NULL,
    old_company_id BIGINT    NOT NULL,
    new_company_id BIGINT    NOT NULL,
    -- managers.company and career_history.company are denormalised text that the merge rewrites
    -- alongside the foreign key. Undo has to restore the old string too, or the picker would keep
    -- suggesting a name that no longer matches anything.
    old_company_text TEXT,
    -- Set when this row had to be deactivated to satisfy a unique index rather than simply moved.
    conflict_action  TEXT
                     CHECK (conflict_action IS NULL OR conflict_action IN ('archived_duplicate'))
);

CREATE INDEX company_merge_records_merge ON company_merge_records (merge_id);
CREATE INDEX company_merge_records_entity ON company_merge_records (entity_type, record_id);

-- ── Redirects ────────────────────────────────────────────────────────────────────────────────
--
-- The merged company's URL keeps resolving, permanently. Separate from manager_url_history (V32),
-- which tracks a manager moving between companies - this is a company's own address surviving the
-- company being absorbed.
CREATE TABLE company_redirects (
    id         BIGSERIAL   PRIMARY KEY,
    old_slug   TEXT        NOT NULL UNIQUE,
    company_id BIGINT      NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX company_redirects_company ON company_redirects (company_id);

-- ── Retiring the source ──────────────────────────────────────────────────────────────────────
--
-- companies.status has no CHECK constraint, so 'merged' needs no schema change - but every public
-- query filters this column, and the filter table in CLAUDE.md is the authority on which values
-- each surface admits. A merged company must appear in none of them.
COMMENT ON COLUMN companies.status IS
    'ghost | approved | merged. A merged company has been absorbed into another: it is never '
    'suggested, listed, or written to, and exists only so its URL and history keep resolving. '
    'See company_merges for what it became.';

-- Finding a company''s merge target without a join, which the redirect handler does on every hit
-- to a retired slug.
CREATE INDEX companies_merged_status ON companies (status) WHERE status = 'merged';
