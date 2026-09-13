-- A sixth thing to rate about an interview: whether it actually assessed the job.
--
-- The five existing categories are all about conduct - how well they communicated, whether they
-- respected your time, how clear they were about the role, whether the process felt fair, whether
-- they told you what happened next. Every one of them is about how a company *behaved* toward a
-- candidate. None of them asks about the substance of what was tested.
--
-- That is the gap, and it is the most common substantive complaint about hiring: take-homes that
-- bear no resemblance to the work, brainteasers, whiteboard puzzles for jobs that involve neither.
-- A company can score well on all five above and still put people through an assessment that told
-- it nothing useful and told the candidate the company does not know what it is hiring for. It is
-- also the most actionable thing on the list: "candidates say our process does not test the job"
-- is a finding a hiring manager can do something about this quarter.
--
-- It has a practical use too. The panel names the three strongest and three weakest categories,
-- and with five you cannot pick three of each without one appearing in both lists - so the tab was
-- showing two and two while the manager and workplace tabs showed three and three. Six makes the
-- ranking work without overlap.
--
-- Nullable, with no backfill and no default. Every interview already on record was written without
-- being asked this, and inventing a score for it would be fabricating an opinion nobody gave. The
-- averages ignore nulls, so existing rows simply do not contribute to this one category; the read
-- path already tolerates a null category because the form has always allowed partial ratings.
ALTER TABLE interview_reviews
    ADD COLUMN job_relevance NUMERIC(2,1) CHECK (job_relevance BETWEEN 0 AND 5);

COMMENT ON COLUMN interview_reviews.job_relevance IS
    'How well the process assessed the actual job, 0-5. Null on experiences written before the question existed.';
