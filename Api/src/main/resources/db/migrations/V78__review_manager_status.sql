-- The manager's status, as the reviewer knew it.
--
-- `reviews` already snapshots the manager's company and title per review, because a manager moves
-- and an opinion records the job it was about rather than the job they hold now. Whether they were
-- still in that role is the same kind of fact and was the one part of the add-manager form the
-- review form could not ask for: the control existed but had nowhere to put its answer.
--
-- The manager's own `status` is then DERIVED from the most current opinion, exactly as their
-- company, title and location already are. That is the property worth having: one contributor
-- cannot overwrite another's answer, and somebody retiring corrects itself as new ratings arrive
-- rather than depending on whoever submitted last.
--
-- Nullable, with no default and no backfill. Every review written before this carries NULL, which
-- reads as "this reviewer did not say" - not as "active". Treating silence as a claim is how a
-- derived column starts asserting things nobody said.
ALTER TABLE reviews ADD COLUMN manager_status TEXT;

COMMENT ON COLUMN reviews.manager_status IS
    'active | retired - the manager''s status as this reviewer knew it, snapshotted per review like manager_company and manager_title. managers.status is derived from the most current review carrying one. NULL means the reviewer did not say.';

-- No CHECK constraint, matching declared_precision: the write path validates the vocabulary, and a
-- constraint would make every future value a migration. The comment is where the vocabulary lives.
