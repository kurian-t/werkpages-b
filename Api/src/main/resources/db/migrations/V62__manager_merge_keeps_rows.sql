-- Merging a manager stops destroying the merged-away row.
--
-- The rule this serves: a review written by a person is only ever soft-deleted. Seeded
-- placeholders may be removed outright, because nobody wrote them.
--
-- That rule was impossible to keep while merging deleted the duplicate manager. reviews.manager_id
-- is ON DELETE CASCADE, so removing the row took its reviews with it - hard, immediately, with no
-- undo. Any review that could not be moved (a true role duplicate, which the unique role indexes
-- forbid holding twice) was therefore destroyed no matter how careful the code above it was.
--
-- So the merged-away manager is kept and marked instead. It is already invisible everywhere:
-- every public surface filters approval_status to 'approved' and 'ghost', so 'rejected' is not
-- listed, not searchable, and its profile 404s. The pointer below records where it went, which
-- also leaves the door open to redirecting its old URLs the way company merges already do.
ALTER TABLE managers ADD COLUMN merged_into BIGINT REFERENCES managers(id) ON DELETE SET NULL;

COMMENT ON COLUMN managers.merged_into IS
    'Set when this manager was merged into another; the row is kept so its reviews survive.';

CREATE INDEX managers_merged_into ON managers (merged_into) WHERE merged_into IS NOT NULL;
