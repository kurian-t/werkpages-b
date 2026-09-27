-- Admin review deletion: why it was deleted, and by whom.
--
-- Two separate needs, and the second is not optional.
--
-- 1. AUDIT. A confidence debit that arrives with no recorded cause is unexplainable six months
--    later, when somebody asks why an account is sitting at 50. The reason is stored beside the
--    deletion that caused it, not inferred from a log line.
--
-- 2. PERMANENCE. reviews.deleted_at is a THREE DAY soft delete: MaintenanceSweep runs
--    restoreExpiredDeletions() daily, which un-deletes every row whose deleted_at is older than
--    three days and republishes it anonymously. That is correct for a person withdrawing their
--    own rating and changing their mind. Applied to a moderation decision it is a disaster - junk
--    an admin removed would come back to life, live and counting toward the manager's average,
--    three days later, with nothing to show it had ever been actioned.
--
--    deleted_reason is what tells the two apart. A row with a reason was removed by a moderator
--    and must stay removed; a row without one is somebody's own withdrawal and still restores.
--
-- Both columns are nullable, so every existing write path - including RateMyManager's, which
-- does not know about them - keeps working unchanged.

ALTER TABLE reviews ADD COLUMN deleted_reason TEXT
    CHECK (deleted_reason IN ('junk', 'duplicate', 'correction', 'other'));

ALTER TABLE reviews ADD COLUMN deleted_by UUID REFERENCES users(id);

COMMENT ON COLUMN reviews.deleted_reason IS
    'Why a moderator deleted this rating: junk | duplicate | correction | other. '
    'NULL means the author withdrew it themselves, which still auto-restores after 3 days. '
    'Only ''junk'' carries a confidence penalty for the author.';

COMMENT ON COLUMN reviews.deleted_by IS
    'The admin who deleted it. NULL for an author''s own withdrawal.';

-- Finding a moderated deletion is a moderation-queue and audit access pattern, not a hot path,
-- but the restore sweep reads it every day and must never table-scan to skip these.
CREATE INDEX reviews_moderated_deletions
    ON reviews (deleted_at)
    WHERE deleted_reason IS NOT NULL;
