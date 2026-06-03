ALTER TABLE managers
    DROP CONSTRAINT IF EXISTS managers_approval_status_check;

ALTER TABLE managers
    ADD CONSTRAINT managers_approval_status_check
        CHECK (approval_status IN ('pending_approval', 'approved', 'rejected', 'ghost'));
