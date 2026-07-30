ALTER TABLE reviews ADD COLUMN deleted_at TIMESTAMPTZ;

-- Partial index: only indexes the small set of soft-deleted rows
CREATE INDEX idx_reviews_soft_deleted ON reviews(deleted_at) WHERE deleted_at IS NOT NULL;
