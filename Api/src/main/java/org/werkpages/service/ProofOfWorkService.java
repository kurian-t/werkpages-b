package org.werkpages.service;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.ProofChallengeRepository;

import java.util.UUID;

/**
 * Applies the tier decision to a rating that has just been written.
 *
 * <p>Order matters here and is the opposite of the obvious one: the rating is <em>saved first</em>
 * and questioned afterwards. Somebody who has spent two minutes rating a manager should not lose
 * that work to a challenge screen, and the drop-off capture elsewhere in this service already
 * takes the same view. Held means saved, visible to its author, and in the queue — never
 * discarded.
 *
 * <p>The hold is applied inside the caller's transaction, so a rating is never briefly visible
 * and never counts toward the gate for even an instant.
 */
public class ProofOfWorkService {

    /** What the admin queue is told about a rating that publishes anyway. */
    public static final String FLAG_NAME_ONLY_MATCH = "name_matches_high_profile_figure";

    private final ProofChallengeRepository challenges;
    private final ConfidenceRepository confidence;

    public ProofOfWorkService(ProofChallengeRepository challenges, ConfidenceRepository confidence) {
        this.challenges = challenges;
        this.confidence = confidence;
    }

    /**
     * Classifies a submission and, when it is held, withholds the rating and opens a challenge.
     *
     * <p>Anonymous submissions are skipped entirely: with no account there is nothing to gate, no
     * gate to open, and nobody to challenge.
     *
     * @return the rating as it stands after the decision when it was held, else null. The caller
     *         answers the request with this rather than with the row the INSERT returned, which
     *         predates the hold and would tell the client its rating is live when it is not.
     */
    public Future<Row> applyTo(SqlConnection conn, UUID userId, long managerId,
                               UUID reviewId, String firstName, String lastName,
                               Long companyId) {
        if (userId == null) return Future.succeededFuture(null);

        return SubmissionTier.classify(challenges, confidence, userId, managerId,
                                       firstName, lastName, companyId)
            .compose(tier -> stampConfidence(conn, reviewId, userId)
                .compose(v -> switch (tier) {
                    case LIVE -> Future.succeededFuture((Row) null);
                    case LIVE_FLAGGED -> flagForAdmin(conn, reviewId).map((Row) null);
                    default -> hold(conn, userId, managerId, reviewId, tier);
                }));
    }

    /**
     * Records what we thought of the author at the moment of writing.
     *
     * <p>Scores move. When an admin looks at a rating months later, what matters is what we knew
     * when it published, not what we know now.
     */
    private Future<Void> stampConfidence(SqlConnection conn, UUID reviewId, UUID userId) {
        return conn.preparedQuery(
                "UPDATE reviews SET author_confidence = "
                + "(SELECT confidence FROM users WHERE id = $2) WHERE id = $1")
            .execute(Tuple.of(reviewId, userId))
            .mapEmpty();
    }

    private Future<Void> flagForAdmin(SqlConnection conn, UUID reviewId) {
        return conn.preparedQuery("UPDATE reviews SET admin_flag = $2 WHERE id = $1")
            .execute(Tuple.of(reviewId, FLAG_NAME_ONLY_MATCH))
            .mapEmpty();
    }

    /**
     * Withholds the rating and opens the challenge.
     *
     * <p>{@code live_since} is cleared alongside the disposition, because it means "when this
     * became live" and a held rating never did. Clearing it is also what makes the thirty-day
     * standing credit mean thirty days <em>continuously</em> live: a rating that is held, then
     * approved, starts its clock at approval rather than inheriting the wait.
     */
    private Future<Row> hold(SqlConnection conn, UUID userId, long managerId,
                             UUID reviewId, SubmissionTier tier) {
        // RETURNING, so the caller can answer with the rating as it now stands rather than issuing
        // a second query for a row it just wrote.
        return conn.preparedQuery("""
                UPDATE reviews
                SET disposition = 'held', gate_eligible = FALSE, live_since = NULL
                WHERE id = $1
                RETURNING *
                """)
            .execute(Tuple.of(reviewId))
            .compose(updated -> conn.preparedQuery("""
                    INSERT INTO manager_proof_challenges (user_id, manager_id, review_id, reason)
                    VALUES ($1, $2, $3, $4)
                    ON CONFLICT DO NOTHING
                    """)
                .execute(Tuple.of(userId, managerId, reviewId, tier.challengeReason()))
                .map(ignored -> updated.iterator().hasNext() ? updated.iterator().next() : null));
    }
}
