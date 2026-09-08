-- A fourth answer to "what was your working relationship": "Something else".
--
-- The original three assumed the reporting line was the only shape a working relationship takes.
-- Plenty are not: a contractor, a rotating secondee, somebody on a joint project for a year.
-- Forcing those people to pick the nearest wrong option makes the claim harder for an admin to
-- read, not easier, because it hides the very thing that would have explained the overlap.
--
-- A separate migration rather than an edit to V60. V60 is applied already, and rewriting an
-- applied migration is how a checksum mismatch stops the app booting - the rule holds even while
-- the only database that has it is a local one.
ALTER TABLE manager_proof_challenges
    DROP CONSTRAINT IF EXISTS manager_proof_challenges_relationship_check;

ALTER TABLE manager_proof_challenges
    ADD CONSTRAINT manager_proof_challenges_relationship_check
    CHECK (relationship IS NULL
           OR relationship IN ('direct_report', 'skip_level', 'other_team', 'other'));
