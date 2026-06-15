-- Placeholder reviews (weight = true) are system-generated ratings used to seed ghost
-- manager profiles until real reviews arrive. weight_expires_on is set to CURRENT_DATE + 14
-- when the first real review is submitted; the pg_cron job then hard-deletes and recalculates.

ALTER TABLE reviews ADD COLUMN weight          BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE reviews ADD COLUMN weight_expires_on DATE;

CREATE INDEX reviews_weight_idx ON reviews (manager_id, weight) WHERE weight = TRUE;

-- Nightly cleanup via pg_cron (requires pg_cron extension; skipped silently in dev/test)
DO $$
BEGIN
    PERFORM cron.schedule(
        'cleanup-expired-weight-reviews',
        '0 3 * * *',
        $cron$
            WITH deleted AS (
                DELETE FROM reviews
                WHERE weight = TRUE
                  AND weight_expires_on IS NOT NULL
                  AND weight_expires_on <= CURRENT_DATE
                RETURNING manager_id
            )
            UPDATE managers m SET
                overall_rating = (
                    SELECT CASE WHEN COUNT(*) > 0
                                THEN ROUND(AVG(overall_rating)::NUMERIC, 1)
                                ELSE NULL END
                    FROM reviews r WHERE r.manager_id = m.id
                ),
                reviews_count = (
                    SELECT COUNT(*)::INTEGER FROM reviews r WHERE r.manager_id = m.id
                ),
                category_averages = (
                    SELECT CASE WHEN COUNT(*) > 0 THEN json_build_object(
                        'Communication Style',               ROUND(AVG(communication_style)::NUMERIC, 1),
                        'Perceived Approachability',         ROUND(AVG(perceived_approachability)::NUMERIC, 1),
                        'Perceived Clarity of Expectations', ROUND(AVG(perceived_clarity_of_expectations)::NUMERIC, 1),
                        'Feedback Style',                    ROUND(AVG(feedback_style)::NUMERIC, 1),
                        'Perceived Supportiveness',          ROUND(AVG(perceived_supportiveness)::NUMERIC, 1),
                        'Decision Making Style',             ROUND(AVG(decision_making_style)::NUMERIC, 1),
                        'Organization and Planning Style',   ROUND(AVG(organization_and_planning_style)::NUMERIC, 1),
                        'Delegation Style',                  ROUND(AVG(delegation_style)::NUMERIC, 1),
                        'Perceived Professional Demeanor',   ROUND(AVG(perceived_professional_demeanor)::NUMERIC, 1),
                        'Overall Working Experience',        ROUND(AVG(overall_working_experience)::NUMERIC, 1)
                    )::jsonb ELSE '{}'::jsonb END
                    FROM reviews r WHERE r.manager_id = m.id
                ),
                updated_at = now()
            FROM (SELECT DISTINCT manager_id FROM deleted) d
            WHERE m.id = d.manager_id
        $cron$
    );
EXCEPTION WHEN OTHERS THEN
    NULL; -- pg_cron not available (test containers, local dev) — skip
END $$;
