-- When the interviewing actually happened, rather than only which year.
--
-- The form asked for a year and nothing else, while the manager and workplace forms both ask for a
-- range. The same question in three places gave three different answers, and a year is a poor one:
-- a process in January and a process in December are a year apart and indistinguishable.
--
-- A process can be a single day, so the two columns are allowed to hold the same value. Month
-- precision, matching the other two forms: nobody remembers the day, and asking for one invites
-- invention.

ALTER TABLE interview_reviews
    ADD COLUMN IF NOT EXISTS interviewed_from  DATE,
    ADD COLUMN IF NOT EXISTS interviewed_until DATE;

-- Nullable, and interview_year stays, because every existing row has a year and no range. Backfill
-- the start from it so old rows sort and filter alongside new ones; the end stays null, since
-- "sometime in 2023" genuinely does not say when the process finished.
UPDATE interview_reviews
   SET interviewed_from = make_date(interview_year, 1, 1)
 WHERE interviewed_from IS NULL
   AND interview_year IS NOT NULL;

-- The end may equal the start - a single-day process - but never precede it.
ALTER TABLE interview_reviews
    DROP CONSTRAINT IF EXISTS interview_reviews_date_range;
ALTER TABLE interview_reviews
    ADD CONSTRAINT interview_reviews_date_range
    CHECK (interviewed_until IS NULL
        OR interviewed_from IS NULL
        OR interviewed_until >= interviewed_from);

COMMENT ON COLUMN interview_reviews.interviewed_from IS
    'Start of the interview process, month precision. Backfilled to January of interview_year for '
    'rows written before the range existed.';

COMMENT ON COLUMN interview_reviews.interviewed_until IS
    'End of the process. May equal interviewed_from: a single-day process is one interview, not a '
    'malformed range. Null on backfilled rows, where the end was never recorded.';
