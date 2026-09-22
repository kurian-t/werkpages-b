package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import org.werkpages.service.DeclaredLocation;

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

    /**
     * The client this repository was built on.
     *
     * <p>Exposed so a service can construct a sibling repository on the same connection rather than
     * having one threaded through every constructor overload — the convention this package already
     * follows for stateless collaborators.
     */
    public SqlClient client() { return db; }

    public CompanyReviewRepository(SqlClient db) {
        this.db = db;
    }

    /*
      The declared ladder is read back as well as written, because the form opens on what is
      already stored: a rating being edited has to show the location it was filed against, and a
      field that comes back empty over a stored answer reads as data loss.
    */
    private static final String COLUMNS =
        "id, company_id, user_id, overall_rating, " + String.join(", ", CATEGORIES)
        + ", worked_from, worked_until, author, created_at, updated_at, "
        + "declared_country, declared_state, declared_city, declared_precision, company_location_id";

    /**
     * Inserts, or replaces this person's existing rating of this company.
     *
     * <p>Upsert rather than insert: one rating per person per company is the rule, and someone
     * revisiting the form means to change their answer, not to be told they already answered.
     * The unique index is partial on {@code deleted_at}, so this targets it explicitly.
     */
    public Future<Row> upsert(long companyId, UUID userId, double overall, List<Double> categories,
                              LocalDate workedFrom, LocalDate workedUntil, String author,
                              DeclaredLocation declared) {
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

        DeclaredLocation loc = declared == null ? DeclaredLocation.NONE : declared;

        Tuple tuple = Tuple.of(companyId, userId, overall, workedFrom);
        for (Double c : categories) tuple.addDouble(c);
        tuple.addValue(workedUntil);
        tuple.addValue(author);
        tuple.addValue(loc.country());
        tuple.addValue(loc.state());
        tuple.addValue(loc.city());
        tuple.addValue(loc.precision());
        tuple.addValue(loc.companyLocationId());

        int workedUntilParam = 5 + CATEGORIES.size();
        int authorParam      = workedUntilParam + 1;
        int countryParam     = authorParam + 1;
        int stateParam       = countryParam + 1;
        int cityParam        = stateParam + 1;
        int precisionParam   = cityParam + 1;
        int locationIdParam  = precisionParam + 1;

        return db.preparedQuery("""
                INSERT INTO company_reviews (company_id, user_id, overall_rating, worked_from, %s, worked_until, author,
                                             declared_country, declared_state, declared_city,
                                             declared_precision, company_location_id)
                VALUES ($1, $2, $3, $4%s, $%d, $%d, $%d, $%d, $%d, $%d, $%d)
                ON CONFLICT (user_id, company_id) WHERE deleted_at IS NULL
                DO UPDATE SET overall_rating = EXCLUDED.overall_rating,
                              %s
                              worked_from  = EXCLUDED.worked_from,
                              worked_until = EXCLUDED.worked_until,
                              -- Kept, not overwritten, when an edit omits it: the handle is the
                              -- identity a reader already saw on this rating, and silently
                              -- replacing it on an edit would make one person look like two.
                              author       = COALESCE(EXCLUDED.author, company_reviews.author),
                              -- All five move together, or none of them do.
                              --
                              -- Not COALESCE per column: coarsening an exact pick to a city sends
                              -- a precision with no location id, and keeping the old id column by
                              -- column would leave a row claiming 'city' while still pointing at a
                              -- building. A submission that declares nothing keeps what is stored,
                              -- so an older client cannot silently erase a location it never knew
                              -- to send.
                              declared_country    = CASE WHEN EXCLUDED.declared_precision IS NULL
                                                    THEN company_reviews.declared_country    ELSE EXCLUDED.declared_country    END,
                              declared_state      = CASE WHEN EXCLUDED.declared_precision IS NULL
                                                    THEN company_reviews.declared_state      ELSE EXCLUDED.declared_state      END,
                              declared_city       = CASE WHEN EXCLUDED.declared_precision IS NULL
                                                    THEN company_reviews.declared_city       ELSE EXCLUDED.declared_city       END,
                              company_location_id = CASE WHEN EXCLUDED.declared_precision IS NULL
                                                    THEN company_reviews.company_location_id ELSE EXCLUDED.company_location_id END,
                              declared_precision  = COALESCE(EXCLUDED.declared_precision, company_reviews.declared_precision),
                              updated_at   = now()
                RETURNING %s
                """.formatted(cols, placeholders, workedUntilParam, authorParam,
                              countryParam, stateParam, cityParam, precisionParam, locationIdParam,
                              updates, COLUMNS))
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
     * The individual ratings behind the average.
     *
     * <p>An average alone asks to be taken on trust. Showing the ratings it is made of lets a
     * reader see the spread - whether a 4.2 is everybody saying 4.2 or half saying 5 and half
     * saying 3 - which is the thing an average is worst at conveying.
     *
     * <p>No author name is selected. A company rating is anonymous by construction; the only
     * identity that ever appears beside one is the reader's own, and the caller matches that on
     * user_id rather than reading a name from here.
     */
    public Future<RowSet<Row>> findByCompany(long companyId, int limit, int offset) {
        return db.preparedQuery(
                "SELECT " + COLUMNS + ", worked_from, worked_until, created_at "
                + "FROM company_reviews "
                + "WHERE company_id = $1 AND deleted_at IS NULL "
                + "ORDER BY created_at DESC LIMIT $2 OFFSET $3")
            .execute(Tuple.of(companyId, limit, offset));
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
    /**
     * Soft-deletes one person's rating, returning the company it belonged to.
     *
     * <p>The company id is what the caller needs: removing a rating changes that company's
     * averages, and the read model has to be told which row to recompute. It used to return only
     * a row count, so a delete left {@code company_stats_live} reporting a rating that no longer
     * existed until something unrelated happened to touch the same company.
     *
     * <p>Empty means nothing was deleted - either no such rating, or not this person's.
     */
    public Future<Optional<Long>> softDelete(UUID reviewId, UUID userId) {
        return db.preparedQuery(
                "UPDATE company_reviews SET deleted_at = now(), updated_at = now() "
                + "WHERE id = $1 AND user_id = $2 AND deleted_at IS NULL "
                + "RETURNING company_id")
            .execute(Tuple.of(reviewId, userId))
            .map(rs -> rs.iterator().hasNext()
                ? Optional.of(rs.iterator().next().getLong("company_id"))
                : Optional.empty());
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
