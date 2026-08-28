package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for {@code interview_reviews} and its cached aggregates.
 *
 * <p>Aggregates are read from {@code company_interview_stats}, which a trigger keeps in step —
 * see V48. Nothing here writes that table.
 */
public class InterviewRepository {

    /** Rating columns, in display order. Overall is separate; difficulty is not a rating. */
    public static final List<String> CATEGORIES = List.of(
        "communication", "respect_for_time", "role_clarity",
        "process_fairness", "next_step_transparency"
    );

    private final SqlClient db;

    public InterviewRepository(SqlClient db) {
        this.db = db;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public Future<Row> create(long companyId, UUID userId, BigDecimal overall,
                              BigDecimal communication, BigDecimal respectForTime,
                              BigDecimal roleClarity, BigDecimal processFairness,
                              BigDecimal nextStepTransparency, Integer difficulty,
                              String outcome, String interviewType, Integer rounds,
                              String processLength, String roleCategory, int interviewYear) {
        return db.preparedQuery("""
                INSERT INTO interview_reviews
                    (company_id, user_id, overall_rating, communication, respect_for_time,
                     role_clarity, process_fairness, next_step_transparency, difficulty,
                     outcome, interview_type, rounds, process_length, role_category, interview_year)
                VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
                RETURNING *
                """)
            .execute(Tuple.of(companyId, userId, overall, communication, respectForTime,
                              roleClarity, processFairness, nextStepTransparency, difficulty,
                              outcome, interviewType, rounds, processLength, roleCategory, interviewYear))
            .map(rs -> rs.iterator().next());
    }

    /** Soft delete, matching the pattern used for manager reviews. */
    public Future<Boolean> softDelete(UUID reviewId, UUID userId) {
        return db.preparedQuery("""
                UPDATE interview_reviews SET deleted_at = now(), updated_at = now()
                WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL
                """)
            .execute(Tuple.of(reviewId, userId))
            .map(rs -> rs.rowCount() > 0);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Cached headline aggregates for a company, or empty when it has no interview reviews. */
    public Future<Optional<Row>> findStats(long companyId) {
        return db.preparedQuery("SELECT * FROM company_interview_stats WHERE company_id = $1")
            .execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /**
     * Category averages plus counts for one company, computed live so the caller can slice by
     * outcome, role and year. The cached table holds only the headline numbers; these segments
     * multiply too fast to cache usefully.
     *
     * @param outcome  "offer" / "no_offer" / "withdrew" / "pending", or null for all
     * @param role     role_category to filter on, or null for all
     * @param sinceYear lowest interview_year to include, or null for all time
     * @return always a row; zero matches give COUNT 0 with NULL averages
     */
    public Future<Row> findBreakdown(long companyId, String outcome, String role, Integer sinceYear) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)                                        AS review_count,
                       ROUND(AVG(overall_rating)::NUMERIC, 1)          AS overall_rating,
                       ROUND(AVG(communication)::NUMERIC, 1)           AS communication,
                       ROUND(AVG(respect_for_time)::NUMERIC, 1)        AS respect_for_time,
                       ROUND(AVG(role_clarity)::NUMERIC, 1)            AS role_clarity,
                       ROUND(AVG(process_fairness)::NUMERIC, 1)        AS process_fairness,
                       ROUND(AVG(next_step_transparency)::NUMERIC, 1)  AS next_step_transparency,
                       ROUND(AVG(difficulty)::NUMERIC, 1)              AS difficulty,
                       PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY rounds) AS median_rounds
                FROM interview_reviews
                WHERE company_id = $1 AND deleted_at IS NULL
                """);
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        if (outcome != null)   { args.add(outcome);   sql.append(" AND outcome = $").append(args.size()); }
        if (role != null)      { args.add(role);      sql.append(" AND role_category = $").append(args.size()); }
        if (sinceYear != null) { args.add(sinceYear); sql.append(" AND interview_year >= $").append(args.size()); }

        // An aggregate with no GROUP BY always yields exactly one row — zero matching reviews give
        // a row of COUNT 0 and NULL averages, not an empty result. Returning Optional here would
        // imply a case that cannot occur and force every caller to handle it.
        return db.preparedQuery(sql.toString())
            .execute(Tuple.from(args))
            .map(rs -> rs.iterator().next());
    }

    /** One row per outcome, for the offer / no-offer split shown on the company page. */
    public Future<RowSet<Row>> findOutcomeSplit(long companyId, String role, Integer sinceYear) {
        StringBuilder sql = new StringBuilder("""
                SELECT outcome,
                       COUNT(*)                               AS review_count,
                       ROUND(AVG(overall_rating)::NUMERIC, 1) AS overall_rating
                FROM interview_reviews
                WHERE company_id = $1 AND deleted_at IS NULL
                """);
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        if (role != null)      { args.add(role);      sql.append(" AND role_category = $").append(args.size()); }
        if (sinceYear != null) { args.add(sinceYear); sql.append(" AND interview_year >= $").append(args.size()); }
        sql.append(" GROUP BY outcome ORDER BY outcome");
        return db.preparedQuery(sql.toString()).execute(Tuple.from(args));
    }

    /** Role categories this company actually has reviews for — drives the filter's options. */
    public Future<RowSet<Row>> findRoleCategories(long companyId) {
        return db.preparedQuery("""
                SELECT role_category, COUNT(*) AS review_count
                FROM interview_reviews
                WHERE company_id = $1 AND deleted_at IS NULL AND role_category IS NOT NULL
                GROUP BY role_category
                ORDER BY review_count DESC, role_category ASC
                """)
            .execute(Tuple.of(companyId));
    }

    /** Whether this user has ever left an interview review — the interview contribution gate. */
    public Future<Boolean> hasContributed(UUID userId) {
        return db.preparedQuery(
                "SELECT EXISTS(SELECT 1 FROM interview_reviews WHERE user_id = $1 AND deleted_at IS NULL)")
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getBoolean(0));
    }

    /**
     * Interview reviews a user has filed today. Nothing about an interview can be verified, so a
     * per-day ceiling is the main brake on someone carpet-bombing a competitor's company page.
     * Counts soft-deleted rows too — deleting and resubmitting must not buy a fresh allowance.
     */
    public Future<Integer> countSubmittedTodayByUser(UUID userId) {
        return db.preparedQuery("""
                SELECT COUNT(*) AS c FROM interview_reviews
                WHERE user_id = $1 AND created_at >= date_trunc('day', now())
                """)
            .execute(Tuple.of(userId))
            .map(rs -> rs.iterator().next().getLong("c").intValue());
    }

    /** Guards the one-per-user-per-company-per-year rule ahead of the unique index. */
    public Future<Boolean> existsForYear(UUID userId, long companyId, int interviewYear) {
        return db.preparedQuery("""
                SELECT EXISTS(
                    SELECT 1 FROM interview_reviews
                    WHERE user_id = $1 AND company_id = $2 AND interview_year = $3 AND deleted_at IS NULL)
                """)
            .execute(Tuple.of(userId, companyId, interviewYear))
            .map(rs -> rs.iterator().next().getBoolean(0));
    }

    /** Industry-level averages, for the industry pages. */
    public Future<RowSet<Row>> findIndustryAverages() {
        return db.query("""
                SELECT c.industry,
                       COUNT(*)                                AS review_count,
                       ROUND(AVG(ir.overall_rating)::NUMERIC, 1) AS avg_rating,
                       ROUND(AVG(ir.difficulty)::NUMERIC, 1)     AS avg_difficulty
                FROM interview_reviews ir
                JOIN companies c ON c.id = ir.company_id
                WHERE ir.deleted_at IS NULL AND c.industry IS NOT NULL
                GROUP BY c.industry
                ORDER BY avg_rating DESC NULLS LAST
                """)
            .execute();
    }
}
