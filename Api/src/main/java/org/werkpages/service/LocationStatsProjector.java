package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Maintains the {@code *_location_stats_live} read model.
 *
 * <p>The hierarchy lives in {@link LocationScope}; the arithmetic lives here; nothing else touches
 * these tables. Scattering aggregate maintenance across repositories is how a projection starts
 * disagreeing with its source — one write path learns a rule and the other four never hear about
 * it.
 *
 * <h2>Three properties this depends on</h2>
 *
 * <b>Every update is atomic SQL.</b> Counts move with {@code count = count + EXCLUDED.count} inside
 * an {@code ON CONFLICT DO UPDATE}, never by reading a value into Java and writing it back. Two
 * ratings submitted in the same instant must both be counted; a read-modify-write would let one
 * silently overwrite the other's increment.
 *
 * <b>Every update joins the caller's transaction.</b> The method takes the connection rather than a
 * pool, so a contribution and its projection commit together or not at all. There is no state in
 * which the rating exists and the count does not.
 *
 * <b>Every change is a delta.</b> An edit subtracts the contribution as it was and adds it as it
 * now is — which covers a changed rating, a changed location, a deletion and a restoration with one
 * mechanism rather than four. {@link #applyChange} is the only correct way to edit.
 *
 * <p>A row is never deleted when it reaches zero. Deleting would race with a concurrent insert
 * recreating it, and the cost of keeping it is a handful of rows per company. Readers filter on the
 * count instead; the facet query's partial index exists for exactly that.
 */
public class LocationStatsProjector {

    /**
     * The ten manager-rating dimensions, in the order they appear on {@code reviews}.
     *
     * <p>Every one is nullable, so each carries its own count in the projection: SQL {@code AVG}
     * ignores NULLs, and an average computed from {@code review_count} would be wrong for any
     * category somebody skipped.
     */
    static final List<String> MANAGER_CATEGORIES = List.of(
        "communication_style",
        "perceived_approachability",
        "perceived_clarity_of_expectations",
        "feedback_style",
        "perceived_supportiveness",
        "decision_making_style",
        "organization_and_planning_style",
        "delegation_style",
        "perceived_professional_demeanor",
        "overall_working_experience");

    /**
     * One contribution's measurable content, already resolved to the scopes it counts toward.
     *
     * @param companyId  the company whose projection this belongs to
     * @param scopes     every rung of the hierarchy, from {@link LocationScope#expand}
     * @param overall    the overall rating
     * @param categories one value per {@link #MANAGER_CATEGORIES} entry, same order; nulls allowed
     */
    public record ManagerReviewFacts(long companyId, List<LocationScope> scopes,
                                     BigDecimal overall, List<BigDecimal> categories) {

        public ManagerReviewFacts {
            if (categories.size() != MANAGER_CATEGORIES.size()) {
                throw new IllegalArgumentException(
                    "expected " + MANAGER_CATEGORIES.size() + " categories, got " + categories.size());
            }
        }

        /** Reads the facts straight off a {@code reviews} row. */
        public static ManagerReviewFacts from(Row review, long companyId) {
            List<BigDecimal> values = new ArrayList<>(MANAGER_CATEGORIES.size());
            for (String column : MANAGER_CATEGORIES) values.add(review.getBigDecimal(column));
            return new ManagerReviewFacts(
                companyId,
                LocationScope.expand(
                    review.getString("declared_country"),
                    review.getString("declared_state"),
                    review.getString("declared_city"),
                    review.getLong("company_location_id")),
                review.getBigDecimal("overall_rating"),
                values);
        }
    }

    /** Adds a contribution to every scope it belongs to. */
    public Future<Void> applyManagerReview(SqlClient conn, ManagerReviewFacts facts) {
        return applyManagerReview(conn, facts, 1);
    }

    /** Removes a contribution from every scope it belonged to. */
    public Future<Void> removeManagerReview(SqlClient conn, ManagerReviewFacts facts) {
        return applyManagerReview(conn, facts, -1);
    }

    /**
     * Moves a contribution from what it was to what it now is.
     *
     * <p>Subtract-then-add rather than a computed difference: the two states may not even share a
     * scope — changing a location moves the contribution to an entirely different set of rows — and
     * a difference has nowhere to go in that case.
     */
    public Future<Void> applyChange(SqlClient conn, ManagerReviewFacts before, ManagerReviewFacts after) {
        return applyManagerReview(conn, before, -1)
            .compose(v -> applyManagerReview(conn, after, 1));
    }

    /**
     * Applies a contribution to a named table rather than the live one.
     *
     * <p>Only the rebuild uses this, to accumulate into staging. It goes through the same
     * arithmetic as the live path on purpose: a rebuild that reimplemented the sums could agree
     * with itself and still be wrong, and the two would drift the first time a dimension was added.
     */
    Future<Void> applyManagerReviewTo(SqlClient conn, String table, ManagerReviewFacts facts) {
        return applyManagerReview(conn, table, facts, 1);
    }

    private Future<Void> applyManagerReview(SqlClient conn, ManagerReviewFacts facts, int sign) {
        return applyManagerReview(conn, "manager_location_stats_live", facts, sign);
    }

    private Future<Void> applyManagerReview(SqlClient conn, String table,
                                            ManagerReviewFacts facts, int sign) {
        if (facts == null) return Future.succeededFuture();
        Future<Void> chain = Future.succeededFuture();
        for (LocationScope scope : facts.scopes()) {
            chain = chain.compose(v -> upsertManagerScope(conn, table, facts, scope, sign));
        }
        return chain;
    }

    private Future<Void> upsertManagerScope(SqlClient conn, String table, ManagerReviewFacts facts,
                                            LocationScope scope, int sign) {
        List<Object> params = new ArrayList<>();
        params.add(facts.companyId());
        params.add(scope.type());
        params.add(scope.key());
        params.add((long) sign);                              // review_count
        params.add(signed(facts.overall(), sign));            // rating_sum
        for (BigDecimal value : facts.categories()) {
            params.add(signed(value, sign));                  // <category>_sum
            params.add(value == null ? 0L : (long) sign);     // <category>_count
        }
        return conn.preparedQuery(managerUpsertFor(table))
            .execute(Tuple.tuple(params))
            .mapEmpty();
    }

    /** Null contributes nothing rather than zero — a skipped category is absent, not a rating of 0. */
    private static BigDecimal signed(BigDecimal value, int sign) {
        if (value == null) return BigDecimal.ZERO;
        return sign < 0 ? value.negate() : value;
    }

    /**
     * Built once from {@link #MANAGER_CATEGORIES} rather than written out.
     *
     * <p>Twenty-two columns hand-typed three times over — insert list, values list, conflict clause
     * — is a transcription error waiting to happen, and the failure mode is a category that
     * silently accumulates into the wrong column. The category list above stays explicit; only the
     * boilerplate is generated.
     */
    private static final String MANAGER_UPSERT_LIVE    = buildManagerUpsert("manager_location_stats_live");
    private static final String MANAGER_UPSERT_REBUILD  = buildManagerUpsert("manager_location_stats_rebuild");

    /** Table names are from a closed set here, never from a request — no injection surface. */
    private static String managerUpsertFor(String table) {
        return switch (table) {
            case "manager_location_stats_live"    -> MANAGER_UPSERT_LIVE;
            case "manager_location_stats_rebuild" -> MANAGER_UPSERT_REBUILD;
            default -> throw new IllegalArgumentException("unknown stats table: " + table);
        };
    }

    private static String buildManagerUpsert(String table) {
        List<String> columns = new ArrayList<>(List.of("review_count", "rating_sum"));
        for (String category : MANAGER_CATEGORIES) {
            columns.add(category + "_sum");
            columns.add(category + "_count");
        }

        StringBuilder insertList = new StringBuilder("company_id, scope_type, scope_key");
        StringBuilder valueList  = new StringBuilder("$1, $2, $3");
        StringBuilder updateList = new StringBuilder();
        int param = 4;
        for (String column : columns) {
            insertList.append(", ").append(column);
            valueList.append(", $").append(param++);
            if (updateList.length() > 0) updateList.append(",\n                ");
            updateList.append(column)
                      .append(" = ").append(table).append(".").append(column)
                      .append(" + EXCLUDED.").append(column);
        }

        return """
            INSERT INTO %s (%s)
            VALUES (%s)
            ON CONFLICT (company_id, scope_type, scope_key) DO UPDATE SET
                %s,
                updated_at = now()
            """.formatted(table, insertList, valueList, updateList);
    }
}
