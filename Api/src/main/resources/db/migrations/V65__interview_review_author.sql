-- An interview experience gets an author handle, like a manager review and a workplace rating.
--
-- Manager reviews have always been signed with a randomly generated name ("CoolLynx30"): anonymous,
-- but a consistent identity within the page, so a reader can tell two accounts apart. V63 gave
-- workplace ratings the same thing. Interview experiences were the last surface without one, so a
-- company with five interview accounts rendered as five unattributed cards - one voice, repeated.
--
-- Nullable, and no backfill. Rows written before authors existed genuinely have none, and inventing
-- a handle would attribute a real person's account to a name they never chose. The read path shows
-- those without a byline, exactly as today.
ALTER TABLE interview_reviews ADD COLUMN author TEXT;

COMMENT ON COLUMN interview_reviews.author IS
    'Randomly generated display handle chosen by the author at submission; null for experiences written before authors existed.';
