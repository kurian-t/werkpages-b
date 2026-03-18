-- V5 skipped renaming pending_manager_edits → manager_edits because manager_edits already
-- existed (created manually) but without the correct columns (new_company, new_title,
-- proposed_by, etc.). This migration drops the broken table and replaces it with the
-- properly structured one from V4.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'pending_manager_edits') THEN
        DROP TABLE IF EXISTS manager_edits;
        ALTER TABLE pending_manager_edits RENAME TO manager_edits;
    END IF;
END$$;

-- Add audit columns (safe to re-run — no-op if already present)
ALTER TABLE manager_edits ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
ALTER TABLE manager_edits ADD COLUMN IF NOT EXISTS reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL;

-- Ensure indexes exist under the new name (CREATE INDEX IF NOT EXISTS is idempotent)
CREATE INDEX IF NOT EXISTS idx_manager_edits_manager_id ON manager_edits(manager_id);
CREATE INDEX IF NOT EXISTS idx_manager_edits_status     ON manager_edits(status);
