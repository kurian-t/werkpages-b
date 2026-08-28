-- Where the interview was for.
--
-- Interviewing at a global company in Canada is not the same process as interviewing at the same
-- company in the US, the UK or India: different rounds, different length, different expectations.
-- Averaging them into one number hides the thing a candidate most wants to know.
--
-- This is the country of the POSITION, not where the candidate lives. Someone in Toronto
-- interviewing for a US role went through the US process, and that is what the comparison is
-- about. Stored as the country name to match `managers.country`, so both halves of the site speak
-- the same vocabulary and can eventually be compared to each other.

ALTER TABLE interview_reviews ADD COLUMN country TEXT;

-- Drives the country filter on the company page and, later, the "experience differs by location"
-- comparison. Partial because a review that did not say is not a country anyone can filter to.
CREATE INDEX interview_reviews_country_idx
    ON interview_reviews (company_id, country)
    WHERE country IS NOT NULL AND deleted_at IS NULL;
