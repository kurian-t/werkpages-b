-- A workplace rating gets an author handle, the way a manager review already has one.
--
-- Manager reviews are signed with a randomly generated name ("CoolLynx30") chosen by the person
-- writing them: anonymous, but a consistent identity within the page, so a reader can tell two
-- opinions apart and the author can find their own. Workplace ratings had no such column, so every
-- one of them rendered as "Anonymous employee" and a page of five ratings read as one voice
-- repeated five times.
--
-- Nullable, and no backfill. Existing rows genuinely have no author - nobody chose one - and
-- inventing a handle for them would attribute a real person's rating to a name they never picked.
-- The read path renders those as "Anonymous employee", exactly as today.
ALTER TABLE company_reviews ADD COLUMN author TEXT;

COMMENT ON COLUMN company_reviews.author IS
    'Randomly generated display handle chosen by the author at submission; null for ratings written before authors existed.';
