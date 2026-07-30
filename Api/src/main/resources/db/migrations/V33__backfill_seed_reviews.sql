-- Backfill seed reviews for all approved/ghost managers that have no weighted review.
-- These managers were created before the seed review feature was introduced, or had
-- their seed review creation silently fail.
--
-- Each inserted review gets:
--   - a random author name (adjective + animal + 2-digit number)
--   - ratings in the 3–5 range with overall = average of categories
--   - a random created_at between 1 and 365 days ago
--   - weight = TRUE, weight_expires_on = NULL (no expiry until first real review)
INSERT INTO reviews (
    manager_id,
    user_id,
    author,
    overall_rating,
    communication_style,
    perceived_approachability,
    perceived_clarity_of_expectations,
    feedback_style,
    perceived_supportiveness,
    decision_making_style,
    organization_and_planning_style,
    delegation_style,
    perceived_professional_demeanor,
    overall_working_experience,
    manager_company,
    manager_title,
    worked_from,
    verified,
    helpful_count,
    weight,
    created_at,
    updated_at
)
SELECT
    m.id,
    NULL,
    (ARRAY['Happy','Calm','Bright','Swift','Bold','Kind','Wise','Fair','Keen','Warm'])
        [(floor(random() * 10) + 1)::int]
    || (ARRAY['Falcon','Tiger','Eagle','Wolf','Bison','Crane','Lynx','Otter','Raven','Gecko'])
        [(floor(random() * 10) + 1)::int]
    || (10 + floor(random() * 90)::int)::text,
    -- overall = average of 10 categories, each randomly 3–5
    ROUND((
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3)) +
        (3 + floor(random() * 3))
    )::numeric / 10, 1),
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    (3 + floor(random() * 3))::numeric,
    m.company,
    m.title,
    (CURRENT_DATE - (365 + floor(random() * 365)::int) * INTERVAL '1 day')::date,
    true,
    0,
    true,
    now() - (1 + floor(random() * 364)::int) * INTERVAL '1 day',
    now()
FROM managers m
WHERE m.approval_status IN ('approved', 'ghost')
  AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
  AND NOT EXISTS (
      SELECT 1 FROM reviews r
      WHERE r.manager_id = m.id
        AND r.weight = TRUE
  );

-- Update cached ratings for every manager that just received a seed review.
UPDATE managers m SET
    overall_rating = sub.overall_rating,
    reviews_count  = sub.reviews_count,
    category_averages = sub.category_averages,
    updated_at = now()
FROM (
    SELECT
        r.manager_id,
        COUNT(*)::integer AS reviews_count,
        ROUND(AVG(r.overall_rating)::numeric, 1) AS overall_rating,
        json_build_object(
            'Communication Style',               ROUND(AVG(r.communication_style)::numeric, 1),
            'Perceived Approachability',         ROUND(AVG(r.perceived_approachability)::numeric, 1),
            'Perceived Clarity of Expectations', ROUND(AVG(r.perceived_clarity_of_expectations)::numeric, 1),
            'Feedback Style',                    ROUND(AVG(r.feedback_style)::numeric, 1),
            'Perceived Supportiveness',          ROUND(AVG(r.perceived_supportiveness)::numeric, 1),
            'Decision Making Style',             ROUND(AVG(r.decision_making_style)::numeric, 1),
            'Organization and Planning Style',   ROUND(AVG(r.organization_and_planning_style)::numeric, 1),
            'Delegation Style',                  ROUND(AVG(r.delegation_style)::numeric, 1),
            'Perceived Professional Demeanor',   ROUND(AVG(r.perceived_professional_demeanor)::numeric, 1),
            'Overall Working Experience',        ROUND(AVG(r.overall_working_experience)::numeric, 1)
        )::jsonb AS category_averages
    FROM reviews r
    WHERE r.weight = TRUE
      AND r.weight_expires_on IS NULL
      AND r.deleted_at IS NULL
    GROUP BY r.manager_id
) sub
WHERE m.id = sub.manager_id
  AND m.reviews_count = 0;
