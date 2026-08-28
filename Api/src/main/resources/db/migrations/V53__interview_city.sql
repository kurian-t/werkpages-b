-- City of the position, alongside the country added in V51.
--
-- Inferred from the request's Cloudflare headers rather than asked for, the same way a manager's
-- location already is: one fewer field to fill in, and the answer is usually right. The person can
-- still change the country, and doing so clears an inferred city that no longer belongs to it.
--
-- Not exposed as a filter yet. City-level slices need sample sizes the site does not have, and a
-- comparison built on two reports would be worse than no comparison at all. Captured now so the
-- data exists when it is worth surfacing.

ALTER TABLE interview_reviews ADD COLUMN city TEXT;

CREATE INDEX interview_reviews_city_idx
    ON interview_reviews (company_id, country, city)
    WHERE city IS NOT NULL AND deleted_at IS NULL;
