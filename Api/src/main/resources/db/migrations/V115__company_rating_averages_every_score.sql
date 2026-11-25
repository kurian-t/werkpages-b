-- A company's workplace rating is the AVERAGE OF EVERY SCORE ITS RATERS GAVE.
--
-- WHAT WAS WRONG
--
-- The workplace form asks ten category questions and then an eleventh: "Overall, how was working
-- here?". V59 stored that eleventh answer in company_reviews.overall_rating and made it, alone,
-- the company's rating. The ten were never averaged into anything.
--
-- Reported from production on Compulsion Games: one workplace rating scoring five 5s, four 1s and
-- a 3, displaying a headline of 1.0 directly above the breakdown that prints those ten numbers.
-- Sixteen of the twenty workplace ratings on the site show a figure that is not an average of the
-- scores underneath it.
--
-- A MANAGER rating has never worked that way. It asks ten questions, one of which is literally
-- "overall working experience", and its headline is the average of all ten. So two cards built to
-- look identical, one tab apart, meant different things by the same number.
--
-- WHAT THIS CHANGES, AND WHAT IT DOES NOT
--
-- The eleventh answer is KEPT, in overall_rating, written by the application exactly as before.
-- No row of it is updated or deleted here. What changes is that it is no longer the company's
-- rating on its own: it is one of the eleven scores that rating averages, which is the same
-- treatment "overall working experience" gets inside a manager rating's ten.
--
-- GENERATED ALWAYS, not a trigger and not application code, because a derived number with a
-- writer has a way of drifting from what it is derived from. PostgreSQL recomputes it on every
-- insert and update, nobody can set it, and it cannot disagree with the eleven columns beside it.

ALTER TABLE company_reviews
    ADD COLUMN rating_average NUMERIC(2,1)
    GENERATED ALWAYS AS (ROUND((work_life_balance + compensation_benefits + career_growth
                                + job_security + workload_sustainability + senior_leadership
                                + company_communication + flexibility + inclusion_belonging
                                + tools_resources + overall_rating) / 11, 1)) STORED;

COMMENT ON COLUMN company_reviews.rating_average IS
    'THE RATING: the average of all eleven scores its author gave, the ten categories and their '
    'own overall, to one decimal place. Derived by the database and unwritable. This is the figure '
    'on the Company tab, on each opinion card, and in company_stats_live.workplace_avg_rating.';

COMMENT ON COLUMN company_reviews.overall_rating IS
    'The author''s answer to "Overall, how was working here?" - their own summary judgement, kept '
    'as data AND counted as the eleventh score in rating_average. It is no longer the company''s '
    'rating by itself, which is what made a card read 1.0 over a breakdown averaging 3.2.';

-- ── The projection averages the ratings, not the eleventh answers ────────────
--
-- Copied verbatim from V113 with ONE line changed, the workplace average, marked below. Repeating
-- the whole function is how CREATE OR REPLACE works: there is no way to replace one expression
-- inside it, and letting the rest drift from V113 would be worse than the repetition.

CREATE OR REPLACE FUNCTION company_stats_expected(p_company_ids BIGINT[] DEFAULT NULL)
RETURNS TABLE (
    company_id           BIGINT,
    manager_count        BIGINT,
    total_reviews        BIGINT,
    avg_rating           NUMERIC,
    logo_url             TEXT,
    workplace_count      BIGINT,
    workplace_avg_rating NUMERIC,
    interview_count      BIGINT,
    interview_avg_rating NUMERIC
)
LANGUAGE sql STABLE AS $$
    SELECT c.id,
           COUNT(DISTINCT m.id)::BIGINT,
           COALESCE(SUM(m.reviews_count), 0)::BIGINT,
           -- The average MANAGER rating at this company. Distinct from workplace_avg_rating (the
           -- average rating OF the company) and interview_avg_rating. Three different questions.
           --
           -- WEIGHTED BY HOW MANY RATINGS EACH MANAGER ACTUALLY HAS. This was AVG(m.overall_rating),
           -- which averaged the managers rather than the ratings: a manager with one rating of 1.0
           -- and a manager with ten of 5.0 reported 3.0, when every person who rated anybody here
           -- but one said 5. Only managers who have ratings count, or an unrated manager would drag
           -- the figure toward whatever default their row carries.
           ROUND((SUM(m.overall_rating * m.reviews_count) FILTER (
                      WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0)
                  / NULLIF(SUM(m.reviews_count) FILTER (
                      WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0), 0)
                 )::NUMERIC, 1)::NUMERIC(3,1),
           -- Prefer a logo.dev URL from a manager row, then the company's own, then any manager's.
           -- Unchanged from V47/V89 - this is a display preference, not an aggregate.
           COALESCE(
               MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
               c.logo_url,
               MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)),
           (SELECT COUNT(*) FROM company_reviews cr
             WHERE cr.company_id = c.id AND cr.deleted_at IS NULL)::BIGINT,
           -- CHANGED IN V115: the average of each rating's own eleven scores, not the average
           -- of its author's separate overall answer. That answer still exists, and is now one
           -- of the eleven rather than the whole figure.
           (SELECT ROUND(AVG(cr.rating_average)::NUMERIC, 1) FROM company_reviews cr
             WHERE cr.company_id = c.id AND cr.deleted_at IS NULL)::NUMERIC(3,1),
           (SELECT COUNT(*) FROM interview_reviews ir
             WHERE ir.company_id = c.id AND ir.deleted_at IS NULL)::BIGINT,
           (SELECT ROUND(AVG(ir.overall_rating)::NUMERIC, 1) FROM interview_reviews ir
             WHERE ir.company_id = c.id AND ir.deleted_at IS NULL)::NUMERIC(3,1)
    FROM companies c
    LEFT JOIN managers m ON m.company_id = c.id
        AND m.approval_status IN ('approved', 'ghost')
        AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
    WHERE c.status <> 'merged'
      AND (p_company_ids IS NULL OR c.id = ANY (p_company_ids))
    GROUP BY c.id, c.logo_url;
$$;

-- ── An edit to the categories alone has to refresh the projection ────────────
--
-- V90 gave company_reviews and interview_reviews one shared trigger function, which recomputes a
-- company when company_id, overall_rating or deleted_at changes. That was complete while the
-- workplace figure WAS overall_rating. It is not now: somebody can revise their ten categories
-- and leave their overall answer alone, which changes the company's rating while every column
-- that function looks at stays identical.
--
-- Watching rating_average covers all eleven at once, and covers them exactly: two compensating
-- edits leave the average unchanged, and an unchanged average needs no recomputation.
--
-- interview_reviews keeps the shared function. It has no rating_average, and its headline really
-- is its own asked overall.
CREATE OR REPLACE FUNCTION company_review_company_stats_update() RETURNS trigger AS $$
DECLARE v_ids BIGINT[];
BEGIN
    SELECT array_agg(DISTINCT cid) INTO v_ids FROM (
        SELECT n.company_id AS cid
        FROM new_rows n JOIN old_rows o ON o.id = n.id
        WHERE ROW(o.company_id, o.rating_average, o.deleted_at)
           IS DISTINCT FROM ROW(n.company_id, n.rating_average, n.deleted_at)
        UNION
        SELECT o.company_id
        FROM new_rows n JOIN old_rows o ON o.id = n.id
        WHERE ROW(o.company_id, o.rating_average, o.deleted_at)
           IS DISTINCT FROM ROW(n.company_id, n.rating_average, n.deleted_at)
    ) AS affected
    WHERE cid IS NOT NULL;

    IF v_ids IS NOT NULL AND cardinality(v_ids) > 0 THEN
        PERFORM company_stats_apply(v_ids);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS company_reviews_company_stats_upd ON company_reviews;

CREATE TRIGGER company_reviews_company_stats_upd AFTER UPDATE ON company_reviews
REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION company_review_company_stats_update();

-- ── Correct every company already projected ─────────────────────────────────
--
-- The read table holds averages of the eleventh answer alone. Recomputed through the database's
-- own implementation, which is the only writer (V89), so every company card matches the
-- definition above from the moment this migration finishes.
SELECT company_stats_apply(NULL::BIGINT[]);
