package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

/**
 * Writes to {@code geo_observations} — one row per submission, never updated.
 *
 * <p><b>Not fire-and-forget.</b> Every other piece of background bookkeeping in this codebase
 * discards its result, and that is correct for a cache or a statistic that the next write will
 * recompute. This is neither. It is the evidence you reach for when a manager's ratings look
 * coordinated, and an observation that silently failed to write is indistinguishable from a
 * submission that never happened. So {@link #record} is composed into the caller's transaction and
 * its failure fails the whole operation.
 *
 * <p>There is also local precedent for the other choice going wrong: the un-awaited
 * {@code company_stats} write in {@code ManagerService} is what produces the wandering TRUNCATE
 * deadlocks in the integration suite.
 */
public class GeoObservationRepository {

    // Subject types. Kept as constants rather than an enum because they are written straight into a
    // text column and read back by ad-hoc admin queries; a typo here should be greppable.
    public static final String SUBJECT_MANAGER          = "manager";
    public static final String SUBJECT_REVIEW           = "review";
    public static final String SUBJECT_COMPANY_REVIEW   = "company_review";
    public static final String SUBJECT_INTERVIEW_REVIEW = "interview_review";
    public static final String SUBJECT_SEARCH           = "search";

    public static final String ACTION_CREATE = "create";
    public static final String ACTION_EDIT   = "edit";
    public static final String ACTION_SEARCH = "search";
    public static final String ACTION_REVIEW = "review";

    private final SqlClient db;

    public GeoObservationRepository(SqlClient db) {
        this.db = db;
    }

    /**
     * Records one observation on the caller's connection.
     *
     * <p>Pass the transaction's {@code SqlConnection} so the observation commits or rolls back with
     * the write it describes. Half of a contribution and none of its audit trail is a worse state
     * than neither.
     *
     * @param conn        the transaction to join, or the pool for a standalone write
     * @param subjectType one of the {@code SUBJECT_*} constants
     * @param subjectId   key of the row this describes, as text; null when no row was created
     * @param action      one of the {@code ACTION_*} constants
     * @param observed    what Cloudflare said; {@link GeoObservation#NONE} when it said nothing
     */
    public Future<Void> record(SqlClient conn, String subjectType, String subjectId,
                               String action, GeoObservation observed) {
        GeoObservation geo = observed == null ? GeoObservation.NONE : observed;
        return conn.preparedQuery("""
                    INSERT INTO geo_observations
                        (subject_type, subject_id, action, country, region, city)
                    VALUES ($1, $2, $3, $4, $5, $6)
                    """)
                .execute(Tuple.of(subjectType, subjectId, action,
                                  geo.country(), geo.region(), geo.city()))
                .mapEmpty();
    }

    /** Records an observation outside any transaction, on the pool. */
    public Future<Void> record(String subjectType, String subjectId,
                               String action, GeoObservation observed) {
        return record(db, subjectType, subjectId, action, observed);
    }

    /** Convenience for subjects whose key is numeric. */
    public Future<Void> record(SqlClient conn, String subjectType, long subjectId,
                               String action, GeoObservation observed) {
        return record(conn, subjectType, String.valueOf(subjectId), action, observed);
    }
}
