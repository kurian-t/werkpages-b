-- Workplace and interview averages alongside the manager average, in the company read model.
--
-- A company tile has always shown one number, drawn from managers.overall_rating, and a reader
-- has no way to know that is what it is. "Google 4.6" reads as a verdict on Google; it means
-- "the managers people rated at Google average 4.6", and says nothing about what working there
-- is like or what interviewing there is like. Both of those are separately rated, separately
-- gated, and were nowhere near the tile.
--
-- Added here rather than joined at query time. The listing is the hottest read in the product
-- and company_stats_live exists precisely because aggregating it live was too slow; adding two
-- more aggregates to that query would walk straight back into the problem the table was created
-- to solve. See CLAUDE.md section 21 - a read table is only safe with transactional maintenance,
-- a rebuild and a reconciliation, and all three land with this change.
--
-- Additive and defaulted, so RMM - which reads shared tables without knowing about newer columns
-- - is unaffected: its INSERTs name their columns and its reads go through Row by name.
ALTER TABLE company_stats_live
    ADD COLUMN workplace_count      BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN workplace_avg_rating NUMERIC(3, 1),
    ADD COLUMN interview_count      BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN interview_avg_rating NUMERIC(3, 1);

COMMENT ON COLUMN company_stats_live.workplace_count IS
    'Live workplace ratings for this company (company_reviews, excluding soft-deleted).';
COMMENT ON COLUMN company_stats_live.workplace_avg_rating IS
    'Mean of company_reviews.overall_rating; null when nobody has rated the workplace.';
COMMENT ON COLUMN company_stats_live.interview_count IS
    'Live interview experiences for this company (interview_reviews, excluding soft-deleted).';
COMMENT ON COLUMN company_stats_live.interview_avg_rating IS
    'Mean of interview_reviews.overall_rating; null when nobody has shared an experience.';

-- Backfill from the source tables. Every existing row reports zero for both until this runs,
-- which is wrong for any company that already has workplace or interview data.
UPDATE company_stats_live cs
   SET workplace_count      = (SELECT COUNT(*) FROM company_reviews cr
                                WHERE cr.company_id = cs.company_id AND cr.deleted_at IS NULL),
       workplace_avg_rating = (SELECT ROUND(AVG(cr.overall_rating)::NUMERIC, 1) FROM company_reviews cr
                                WHERE cr.company_id = cs.company_id AND cr.deleted_at IS NULL),
       interview_count      = (SELECT COUNT(*) FROM interview_reviews ir
                                WHERE ir.company_id = cs.company_id AND ir.deleted_at IS NULL),
       interview_avg_rating = (SELECT ROUND(AVG(ir.overall_rating)::NUMERIC, 1) FROM interview_reviews ir
                                WHERE ir.company_id = cs.company_id AND ir.deleted_at IS NULL),
       updated_at           = now();
