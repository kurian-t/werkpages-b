package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds and reconciles the location read model from source.
 *
 * <p>This is what makes a denormalised projection safe rather than frightening. Without it, a bug
 * in the projector leaves derived numbers that are wrong in some unknown way and can only be
 * repaired by hand. With it, the answer to any doubt is "throw it away and recompute it", and the
 * numbers on the page are never more than one command away from being provably correct.
 *
 * <p>It is also the projector's strongest test. The incremental path and the full reconstruction
 * are independent implementations of the same arithmetic — when they agree, that agreement is
 * evidence. {@link #reconcile} exists to check exactly that, and a disagreement means the derived
 * data is wrong, never that the source is.
 *
 * <h2>How a rebuild avoids taking the site down</h2>
 *
 * Recompute into the {@code _rebuild} staging tables, check the result, and only then replace the
 * live contents — all inside one transaction. The live tables are never emptied speculatively, so a
 * rebuild that fails halfway leaves the previous projection serving rather than an empty one.
 */
public class LocationStatsRebuilder {

    private final Pool db;
    private final LocationStatsProjector projector;

    public LocationStatsRebuilder(Pool db, LocationStatsProjector projector) {
        this.db = db;
        this.projector = projector;
    }

    /** What a rebuild did, for the operator running it. */
    public record RebuildResult(long reviewsRead, long scopeRowsWritten) {}

    /**
     * Recomputes the manager-rating projection from {@code reviews} and swaps it in.
     *
     * <p>Only live, undeleted, real opinions count.
     *
     * <p>{@code weight = FALSE} excludes the seed reviews {@code createSeedReview} writes for a new
     * ghost — synthetic placeholders with no author, invented to keep a brand-new profile from
     * looking empty. The incremental path never projects them, because they are not written through
     * the review transaction; a rebuild that counted them would inflate every ghost company's
     * location numbers with ratings nobody gave. The agreement test caught exactly this.
     */
    public Future<RebuildResult> rebuildManagerStats() {
        return db.withTransaction(conn ->
            conn.query("TRUNCATE manager_location_stats_rebuild").execute()
                .compose(v -> readAllLiveReviews(conn))
                .compose(rows -> projectAll(conn, rows))
                .compose(result -> verifyNonNegative(conn, result))
                .compose(result -> swapIn(conn, "manager_location_stats").map(v -> result)));
    }

    private Future<RowSet<Row>> readAllLiveReviews(SqlClient conn) {
        return conn.query("""
                SELECT r.overall_rating, r.declared_country, r.declared_state, r.declared_city,
                       r.company_location_id, m.company_id,
                       r.communication_style, r.perceived_approachability,
                       r.perceived_clarity_of_expectations, r.feedback_style,
                       r.perceived_supportiveness, r.decision_making_style,
                       r.organization_and_planning_style, r.delegation_style,
                       r.perceived_professional_demeanor, r.overall_working_experience
                FROM reviews r
                JOIN managers m ON m.id = r.manager_id
                WHERE r.disposition = 'live' AND r.deleted_at IS NULL
                  AND r.weight = FALSE
                  AND m.company_id IS NOT NULL
                """).execute();
    }

    /**
     * Applies every contribution through the same projector the live path uses, writing into the
     * staging table.
     *
     * <p>Reusing the projector is the point: a rebuild that reimplemented the arithmetic could
     * agree with itself and still be wrong, and the two would drift apart the first time a rating
     * dimension was added.
     */
    private Future<RebuildResult> projectAll(SqlClient conn, RowSet<Row> rows) {
        Future<Void> chain = Future.succeededFuture();
        long count = 0;
        for (Row row : rows) {
            var facts = LocationStatsProjector.ManagerReviewFacts.from(row, row.getLong("company_id"));
            chain = chain.compose(v -> projector.applyManagerReviewTo(
                conn, "manager_location_stats_rebuild", facts));
            count++;
        }
        final long read = count;
        return chain.compose(v -> conn.query("SELECT count(*) AS n FROM manager_location_stats_rebuild")
            .execute()
            .map(rs -> new RebuildResult(read, rs.iterator().next().getLong("n"))));
    }

    /**
     * A count or sum below zero means the arithmetic is broken, and promoting it would replace
     * numbers that are merely suspect with numbers that are visibly impossible.
     */
    private Future<RebuildResult> verifyNonNegative(SqlClient conn, RebuildResult result) {
        // On the transaction's own connection: the staged rows are not visible to anybody else yet,
        // so reading them through the pool would check an empty table and pass every time.
        return conn.query("""
                SELECT count(*) AS bad FROM manager_location_stats_rebuild
                WHERE review_count < 0 OR rating_sum < 0
                """).execute()
            .compose(rs -> {
                long bad = rs.iterator().next().getLong("bad");
                if (bad > 0) {
                    return Future.failedFuture(new IllegalStateException(
                        "rebuild produced " + bad + " rows with negative counts or sums; not promoting"));
                }
                return Future.succeededFuture(result);
            });
    }

    /** Replaces the live contents with the staged ones, atomically. */
    private Future<Void> swapIn(SqlClient conn, String table) {
        return conn.query("DELETE FROM " + table + "_live").execute()
            .compose(v -> conn.query(
                "INSERT INTO " + table + "_live SELECT * FROM " + table + "_rebuild").execute())
            .mapEmpty();
    }

    // ── Reconciliation ────────────────────────────────────────────────────────

    /** One scope whose projected total disagrees with the source. */
    public record Drift(long companyId, String scopeType, String scopeKey,
                        long projected, long actual) {
        @Override public String toString() {
            return "company=" + companyId + " " + scopeType + ":" + scopeKey
                 + " projected=" + projected + " actual=" + actual;
        }
    }

    /**
     * Compares company-wide projected counts against the source and reports every disagreement.
     *
     * <p>Company-wide is checked because it is the one scope every contribution reaches, so a
     * contribution missed by the projector cannot hide in it. Silent divergence is the real failure
     * mode of a read model — not being wrong once, but being wrong unnoticed — so this is meant to
     * run on a schedule and shout, rather than to be invoked when somebody already suspects a
     * problem.
     */
    public Future<List<Drift>> reconcile() {
        return db.query("""
                SELECT COALESCE(p.company_id, s.company_id) AS company_id,
                       COALESCE(p.review_count, 0)          AS projected,
                       COALESCE(s.actual, 0)                AS actual
                FROM (SELECT company_id, review_count
                        FROM manager_location_stats_live
                       WHERE scope_type = 'company') p
                FULL OUTER JOIN (
                      SELECT m.company_id, count(*) AS actual
                        FROM reviews r
                        JOIN managers m ON m.id = r.manager_id
                       WHERE r.disposition = 'live' AND r.deleted_at IS NULL
                         AND r.weight = FALSE
                         AND m.company_id IS NOT NULL
                       GROUP BY m.company_id) s
                  ON s.company_id = p.company_id
                WHERE COALESCE(p.review_count, 0) <> COALESCE(s.actual, 0)
                """).execute()
            .map(rs -> {
                List<Drift> drifts = new ArrayList<>();
                for (Row row : rs) {
                    drifts.add(new Drift(row.getLong("company_id"), "company", "",
                                         row.getLong("projected"), row.getLong("actual")));
                }
                return drifts;
            });
    }
}
