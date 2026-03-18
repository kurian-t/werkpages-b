-- Rename pending_manager_edits → manager_edits (serves as both queue and audit history)
-- Guard: only rename if the old table exists and the new one doesn't yet
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'pending_manager_edits')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'manager_edits') THEN
        ALTER TABLE pending_manager_edits RENAME TO manager_edits;
    END IF;
END$$;

-- Add audit columns if they don't already exist
ALTER TABLE manager_edits ADD COLUMN IF NOT EXISTS reviewed_at  TIMESTAMPTZ;
ALTER TABLE manager_edits ADD COLUMN IF NOT EXISTS reviewed_by  UUID REFERENCES users(id) ON DELETE SET NULL;

-- Rename indexes to match new table name (only if old name exists AND new name doesn't)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_pending_edits_manager_id')
       AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_manager_edits_manager_id') THEN
        ALTER INDEX idx_pending_edits_manager_id RENAME TO idx_manager_edits_manager_id;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_pending_edits_status')
       AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_manager_edits_status') THEN
        ALTER INDEX idx_pending_edits_status RENAME TO idx_manager_edits_status;
    END IF;
END$$;
