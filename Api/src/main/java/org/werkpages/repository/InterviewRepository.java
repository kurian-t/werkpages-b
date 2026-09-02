package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
                              String outcome, Integer rounds, String processLength,
                              String roleCategory, String country, String city, int interviewYear) {
        return db.preparedQuery("""
                INSERT INTO interview_reviews
                    (company_id, user_id, overall_rating, communication, respect_for_time,
                     role_clarity, process_fairness, next_step_transparency, difficulty,
                     outcome, rounds, process_length, role_category, country, city, interview_year)
                VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)
                RETURNING *
                """)
            .execute(Tuple.of(companyId, userId, overall, communication, respectForTime,
                              roleClarity, processFairness, nextStepTransparency, difficulty,
                              outcome, rounds, processLength, roleCategory, country, city, interviewYear))
            .map(rs -> rs.iterator().next());
    }

    /**
     * Records the shape of a process: one row per round, in order.
     *
     * <p>The count on {@code interview_reviews.rounds} is maintained by V50's trigger, so it is
     * never written here — the detail is the source of truth and the count follows it.
     */
    public Future<Void> insertRounds(UUID reviewId, List<String> roundTypes) {
        if (roundTypes == null || roundTypes.isEmpty()) return Future.succeededFuture();

        List<Tuple> batch = new ArrayList<>();
        for (int i = 0; i < roundTypes.size(); i++) {
            batch.add(Tuple.of(reviewId, i + 1, roundTypes.get(i)));
        }
        return db.preparedQuery("""
                INSERT INTO interview_review_rounds (interview_review_id, round_number, round_type)
                VALUES ($1, $2, $3)
                """)
            .executeBatch(batch)
            .mapEmpty();
    }

    /** The rounds of one review, in order. */
    public Future<RowSet<Row>> findRounds(UUID reviewId) {
        return db.preparedQuery("""
                SELECT round_number, round_type
                FROM interview_review_rounds
                WHERE interview_review_id = $1
                ORDER BY round_number
                """)
            .execute(Tuple.of(reviewId));
    }

    /**
     * The most commonly reported shape of a company's process, as an ordered list of round types.
     *
     * <p>Mode per position rather than an average: "usually a phone screen, then a technical, then
     * a panel" is what someone deciding whether to apply actually wants, and averaging formats is
     * meaningless.
     */
    public Future<RowSet<Row>> findTypicalRounds(long companyId) {
        return db.preparedQuery("""
                SELECT r.round_number,
                       MODE() WITHIN GROUP (ORDER BY r.round_type) AS round_type,
                       COUNT(*)                                    AS reported_by
                FROM interview_review_rounds r
                JOIN interview_reviews ir ON ir.id = r.interview_review_id
                WHERE ir.company_id = $1 AND ir.deleted_at IS NULL
                GROUP BY r.round_number
                ORDER BY r.round_number
                """)
            .execute(Tuple.of(companyId));
    }

    /**
     * Soft delete, matching manager reviews exactly.
     *
     * <p>The author is cleared in the same statement as the hide, so the row stops being
     * attributable the moment someone asks for it to go, rather than only once it resurfaces.
     *
     * @return the company the review belonged to, or empty if it was not this user's to delete
     */
    public Future<Optional<Long>> softDelete(UUID reviewId, UUID userId) {
        return db.preparedQuery("""
                UPDATE interview_reviews
                SET deleted_at = now(), updated_at = now(), user_id = NULL
                WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL
                RETURNING company_id
                """)
            .execute(Tuple.of(reviewId, userId))
            .map(rs -> rs.iterator().hasNext()
                ? Optional.of(rs.iterator().next().getLong("company_id"))
                : Optional.empty());
    }

    /** Records a deletion so the same person cannot immediately replace what will come back. */
    public Future<Void> recordDeletion(UUID userId, long companyId) {
        return db.preparedQuery(
                "INSERT INTO interview_review_deletions (user_id, company_id) VALUES ($1, $2)")
            .execute(Tuple.of(userId, companyId))
            .mapEmpty();
    }

    /**
     * Brings back reviews whose soft-delete window has passed, now anonymous.
     *
     * <p>Three days, the same window manager reviews use: long enough that a deletion made in
     * anger is genuinely gone from the page, short enough that a company cannot rely on pressure
     * to permanently remove feedback.
     */
    public Future<Integer> restoreExpiredDeletions() {
        return db.preparedQuery("""
                UPDATE interview_reviews SET deleted_at = NULL
                WHERE deleted_at IS NOT NULL AND deleted_at < now() - INTERVAL '3 days'
                """)
            .execute()
            .map(RowSet::rowCount);
    }

    /** Most recent deletion for this user and company inside the cooldown, if any. */
    public Future<Optional<OffsetDateTime>> findRecentDeletion(UUID userId, long companyId) {
        return db.preparedQuery("""
                SELECT deleted_at FROM interview_review_deletions
                WHERE user_id = $1 AND company_id = $2
                  AND deleted_at > now() - make_interval(days => $3)
                ORDER BY deleted_at DESC
                LIMIT 1
                """)
            .execute(Tuple.of(userId, companyId, org.werkpages.service.SubmissionLimits.COOLDOWN_DAYS))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next().getOffsetDateTime("deleted_at"))
                : Optional.empty());
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Cached headline aggregates for a company, or empty when it has no interview reviews. */
    public Future<Optional<Row>> findStats(long companyId) {
        return db.preparedQuery("SELECT * FROM company_interview_stats WHERE company_id = $1")
            .execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /**
     * Company-wide category averages and counts, unfiltered.
     *
     * <p>Deliberately takes no filters. These numbers back the headline summary, which must not
     * move when someone explores the chart below it - a company's rating changing from 3.7 to 2.9
     * because a filter was clicked reads as the page contradicting itself.
     */
    public Future<Row> findBreakdown(long companyId) {
        return db.preparedQuery("""
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
                """)
            .execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().next());
    }

    /**
     * Every category rated three ways at once: by everyone, by those who got an offer, and by
     * those who did not.
     *
     * <p>One query rather than three round trips, and one row rather than three, so the three
     * series are guaranteed to describe the same population. Showing them together is the point:
     * making someone click "Offer", memorise a number, click "No offer" and compare in their head
     * is worse than putting the two bars next to each other.
     *
     * @param role restrict to one role category, or null for every role
     */
    public Future<Row> findCategoryComparison(long companyId, String role, String country) {
        StringBuilder sql = new StringBuilder("SELECT\n");
        sql.append("    COUNT(*) AS all_count,\n");
        sql.append("    COUNT(*) FILTER (WHERE outcome = 'offer')    AS offer_count,\n");
        sql.append("    COUNT(*) FILTER (WHERE outcome = 'no_offer') AS no_offer_count");

        // Built from the canonical list so a new category cannot be forgotten in one of the three
        // series and silently render as "not rated".
        List<String> columns = new ArrayList<>(CATEGORIES);
        columns.add("overall_rating");
        for (String column : columns) {
            sql.append(",\n    ROUND(AVG(").append(column).append(")::NUMERIC, 1) AS ").append(column).append("_all");
            sql.append(",\n    ROUND(AVG(").append(column).append(") FILTER (WHERE outcome = 'offer')::NUMERIC, 1) AS ")
               .append(column).append("_offer");
            sql.append(",\n    ROUND(AVG(").append(column).append(") FILTER (WHERE outcome = 'no_offer')::NUMERIC, 1) AS ")
               .append(column).append("_no_offer");
        }
        sql.append("\nFROM interview_reviews\nWHERE company_id = $1 AND deleted_at IS NULL");

        List<Object> args = new ArrayList<>();
        args.add(companyId);
        if (role != null)    { args.add(role);    sql.append(" AND role_category = $").append(args.size()); }
        if (country != null) { args.add(country); sql.append(" AND country = $").append(args.size()); }

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

    /** Countries this company has interviews for, most reported first. Drives the filter options. */
    public Future<RowSet<Row>> findCountries(long companyId) {
        return db.preparedQuery("""
                SELECT country, COUNT(*) AS review_count
                FROM interview_reviews
                WHERE company_id = $1 AND deleted_at IS NULL AND country IS NOT NULL
                GROUP BY country
                ORDER BY review_count DESC, country ASC
                """)
            .execute(Tuple.of(companyId));
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

    /**
     * This user's own live review for one company, if they have one.
     *
     * <p>Drives the ownership control on the company page: once you have contributed, the call to
     * action stops asking and starts offering to edit or remove what you wrote.
     */
    public Future<Optional<Row>> findMineForCompany(UUID userId, long companyId) {
        return db.preparedQuery("""
                SELECT * FROM interview_reviews
                WHERE user_id = $1 AND company_id = $2 AND deleted_at IS NULL
                ORDER BY interview_year DESC
                LIMIT 1
                """)
            .execute(Tuple.of(userId, companyId))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /** Replaces the content of a review the caller owns. Returns empty if it is not theirs. */
    public Future<Optional<Row>> update(UUID reviewId, UUID userId, BigDecimal overall,
                                        BigDecimal communication, BigDecimal respectForTime,
                                        BigDecimal roleClarity, BigDecimal processFairness,
                                        BigDecimal nextStepTransparency, Integer difficulty,
                                        String outcome, Integer rounds, String processLength,
                                        String roleCategory, String country, String city, int interviewYear) {
        return db.preparedQuery("""
                UPDATE interview_reviews SET
                    overall_rating = $3, communication = $4, respect_for_time = $5,
                    role_clarity = $6, process_fairness = $7, next_step_transparency = $8,
                    difficulty = $9, outcome = $10, rounds = $11, process_length = $12,
                    role_category = $13, country = $14, city = $15, interview_year = $16, updated_at = now()
                WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL
                RETURNING *
                """)
            .execute(Tuple.of(reviewId, userId, overall, communication, respectForTime,
                              roleClarity, processFairness, nextStepTransparency, difficulty,
                              outcome, rounds, processLength, roleCategory, country, city, interviewYear))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /** Clears a review's rounds so an edit can write the new list in their place. */
    public Future<Void> deleteRounds(UUID reviewId) {
        return db.preparedQuery("DELETE FROM interview_review_rounds WHERE interview_review_id = $1")
            .execute(Tuple.of(reviewId))
            .mapEmpty();
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
                SELECT (
                    SELECT COUNT(*) FROM interview_reviews
                    WHERE user_id = $1 AND created_at >= date_trunc('day', now())
                ) + (
                    -- Deleting clears user_id, so a deleted review is invisible to the query
                    -- above. Counting today's deletions as well is what stops delete-and-resubmit
                    -- refunding the day's allowance.
                    SELECT COUNT(*) FROM interview_review_deletions
                    WHERE user_id = $1 AND deleted_at >= date_trunc('day', now())
                ) AS c
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
