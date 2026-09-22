package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * Whether a rating is published, withheld pending proof, or refused.
 *
 * <p>Three files used to write this column — proof-of-work holding a suspicious submission, an
 * admin approving one, an admin rejecting one — and each carried its own copy of the companion
 * columns and its own obligation to a derived table. None of them met the second obligation, so
 * holding a rating left it counted in the location figures it had just been removed from, and
 * releasing one never put it back.
 *
 * <p>Stateless, and constructed in place by each caller for the same reason
 * {@code ProofOfWorkService} and {@code GeoObservationRepository} are: threading it through every
 * constructor overload buys nothing.
 */
public final class ReviewDisposition {

    public static final String LIVE     = "live";
    public static final String HELD     = "held";
    public static final String REJECTED = "rejected";

    private final LocationStatsProjector locationStats = new LocationStatsProjector();

    /**
     * Sets the disposition, its companion columns, and moves the rating's contribution to match.
     *
     * <p>{@code live_since} means "when this became live", so it starts at release rather than at
     * submission — which is what makes the thirty-day standing credit mean thirty days
     * <em>continuously</em> live, rather than inheriting the wait spent held.
     *
     * <p>Runs in the caller's transaction on purpose. A projection updated in a second transaction
     * is a projection that drifts the first time the second one fails.
     */
    public Future<Void> set(SqlClient conn, UUID reviewId, String disposition) {
        boolean live = LIVE.equals(disposition);
        return locationStats.contributionOf(conn, reviewId)
            .compose(before -> conn.preparedQuery("""
                    UPDATE reviews
                       SET disposition   = $2,
                           gate_eligible = $3,
                           live_since    = CASE WHEN $3 THEN now() ELSE NULL END
                     WHERE id = $1
                    """)
                .execute(Tuple.of(reviewId, disposition, live))
                .compose(v -> locationStats.resyncManagerReview(conn, reviewId, before)));
    }
}
