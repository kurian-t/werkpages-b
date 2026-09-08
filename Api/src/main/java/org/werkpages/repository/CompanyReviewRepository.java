package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for {@code company_reviews} — what an employer is like to work for.
 *
 * <p>Deliberately separate from {@link ReviewRepository}, which holds manager ratings. The two
 * datasets answer different questions and a company page shows both side by side so they can
 * disagree: a good employer with uneven managers is a real and useful thing to be able to see.
 *
 * <p>Averages are computed on read rather than cached. Every category is NOT NULL, so each one
 * averages over the same set of rows and there is no per-category denominator to track — which is
 * the practical reason the form has no N/A.
 */
public class CompanyReviewRepository {

    /** The ten, in display order. Overall is asked separately and is not one of them. */
    public static final List<String> CATEGORIES = List.of(
        "work_life_balance", "compensation_benefits", "career_growth", "job_security",
        "workload_sustainability", "senior_leadership", "company_communication",
        "flexibility", "inclusion_belonging", "tools_resources"
    );

    private final SqlClient db;

    public CompanyReviewRepository(SqlClient db) {
        this.db = db;
    }

    private static final String COLUMNS =
        "id, company_id, user_id, overall_rating, " + String.join(", ", CATEGORIES)
        + ", worked_from, worked_until, created_at, updated_at";

    /**
     * Inserts, or replaces this person's existing rating of this company.
     *
     * <p>Upsert rather than insert: one rating per person per company is the rule, and someone
     * revisiting the form means to change their answer, not to be told they already answered.
     * The unique index is partial on {@code deleted_at}, so this targets it explicitly.
     */
    public Future<Row> upsert(long companyId, UUID userId, double overall, List<Double> categories,
                              LocalDate workedFrom, LocalDate workedUntil) {
        if (categories.size() != CATEGORIES.size()) {
            return Future.failedFuture(
                "Expected " + CATEGORIES.size() + " category ratings, got " + categories.size());
        }
        String cols = String.join(", ", CATEGORIES);
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < CATEGORIES.size(); i++) placeholders.append(", $").append(5 + i);
        StringBuilder updates = new StringBuilder();
        for (int i = 0; i < CATEGORIES.size(); i++) {
            updates.append(CATEGORIES.get(i)).append(" = EXCLUDED.").append(CATEGORIES.get(i)).append(", ");
        }

        Tuple tuple = Tuple.of(companyId, userId, overall, workedFrom);
        for (Double c : categories) tuple.addDouble(c);
        tuple.addValue(workedUntil);

        return db.preparedQuery("""
                INSERT INTO company_reviews (company_id, user_id, overall_rating, worked_from, %s, worked_until)
                VALUES ($1, $2, $3, $4%s, $%d)
                ON CONFLICT (user_id, company_id) WHERE deleted_at IS NULL
                DO UPDATE SET overall_rating = EXCLUDED.overall_rating,
                              %s
                              worked_from  = EXCLUDED.worked_from,
                              worked_until = EXCLUDED.worked_until,
                              updated_at   = now()
                RETURNING %s
                """.formatted(cols, placeholders, 5 + CATEGORIES.size(), updates, COLUMNS))
            .execute(tuple)
            .map(rs -> rs.iterator().next());
    }

    /** This person's rating of this company, if they have one. */
    public Future<Optional<Row>> findByUserAndCompany(UUID userId, long companyId) {
        return db.preparedQuery(
                "SELECT " + COLUMNS + " FROM company_reviews "
                + "WHERE user_id = $1 AND company_id = $2 AND deleted_at IS NULL")
            .execute(Tuple.of(userId, companyId))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /**
     * The company's aggregate, or empty when nobody has rated it.
     *
     * <p>Returns a single row of averages plus the count. Every category is NOT NULL so all ten
     * averages share one denominator, which is what lets the page print a single "based on N"
     * rather than a count per bar.
     */
    public Future<Optional<Row>> findCompanyAggregate(long companyId) {
        StringBuilder avgs = new StringBuilder();
        for (String c : CATEGORIES) {
            avgs.append(", ROUND(AVG(").append(c).append("), 1) AS ").append(c);
        }
        return db.preparedQuery("""
                SELECT COUNT(*) AS rating_count,
                       ROUND(AVG(overall_rating), 1) AS overall_rating%s
                FROM company_reviews
                WHERE company_id = $1 AND deleted_at IS NULL
                """.formatted(avgs))
            .execute(Tuple.of(companyId))
            .map(rs -> {
                Row row = rs.iterator().next();
                // COUNT always returns a row; zero ratings means there is no aggregate to show.
                return row.getLong("rating_count") == 0 ? Optional.<Row>empty() : Optional.of(row);
            });
    }

    /**
     * Soft delete, matching how manager reviews and interview reviews behave.
     *
     * <p>Scoped by user as well as id so the query itself enforces ownership — the service checks
     * too, and neither is a substitute for the other.
     */
    public Future<Integer> softDelete(UUID reviewId, UUID userId) {
        return db.preparedQuery(
                "UPDATE company_reviews SET deleted_at = now(), updated_at = now() "
                + "WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL")
            .execute(Tuple.of(reviewId, userId))
            .map(RowSet::rowCount);
    }

    /**
     * How many people rated both of these companies.
     *
     * <p>Asked before a merge. One rating per person per company is enforced by a unique index, so
     * merging two companies a person rated separately would put two of their rows on one company
     * and violate it. The merge preview blocks on this, exactly as it already does for interview
     * reviews.
     */
    public Future<Long> countRatingCollisions(long keepId, long mergeId) {
        return db.preparedQuery("""
                SELECT COUNT(*) AS c
                FROM company_reviews a
                JOIN company_reviews b ON a.user_id = b.user_id
                WHERE a.company_id = $1 AND b.company_id = $2
                  AND a.deleted_at IS NULL AND b.deleted_at IS NULL
                """)
            .execute(Tuple.of(keepId, mergeId))
            .map(rs -> rs.iterator().next().getLong("c"));
    }
}
