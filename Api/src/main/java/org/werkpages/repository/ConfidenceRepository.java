package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * Per-account confidence, and the log that explains it.
 *
 * <p>The score is a cache; {@code user_confidence_events} is the truth. "This person is at 32" is
 * unactionable — "32, because they abandoned a challenge on Satya Nadella and had a manager
 * rejected as junk" is something an admin can act on, and reverse when it was wrong.
 *
 * <p>The score is never shown to the user and never leaves an admin surface. A visible score
 * teaches people to farm it; people see consequences in plain words instead.
 */
public class ConfidenceRepository {

    /** Every account starts here: comfortably inside {@code normal}, with room to fall. */
    public static final int STARTING_CONFIDENCE = 70;

    /** Below this, submissions are held for a person before they count. */
    public static final int RESTRICTED_BELOW = 25;

    /** Below this, ratings publish but are flagged in the queue. */
    public static final int WATCH_BELOW = 50;

    /**
     * The most that waiting can earn. Trusted has to be granted by a verified affiliation or a
     * person; it is never accumulated by doing nothing for long enough.
     */
    public static final int PASSIVE_CEILING = 79;

    // Reasons. Named because a bare "-15" in a call site tells the next reader nothing.
    public static final String CHALLENGE_APPROVED     = "challenge_approved";
    public static final String AFFILIATION_VERIFIED   = "affiliation_verified";
    public static final String REVIEW_STOOD_30D       = "review_stood_30d";
    public static final String SUSPICIOUS_NAME_HELD   = "suspicious_name_held";
    public static final String CHALLENGE_ABANDONED    = "challenge_abandoned";
    public static final String MANAGER_REJECTED_JUNK  = "manager_rejected_junk";
    public static final String CHALLENGE_REJECTED     = "challenge_rejected";

    private final SqlClient db;

    public ConfidenceRepository(SqlClient db) {
        this.db = db;
    }

    /**
     * Applies one event, exactly once, whatever happens.
     *
     * <p>One statement, because two are not safe. An {@code INSERT ... ON CONFLICT DO NOTHING}
     * followed by a separate {@code UPDATE} leaves the real guarantee to application code
     * remembering to check a row count — and every one of these events can fire twice from a
     * retry, a double-click, a rerun of the daily sweep, or a deploy replaying a queue. Debiting
     * somebody 15 twice for one abandonment silently destroys an account's standing.
     *
     * <p>Here the insert feeds the update: a replay conflicts, {@code logged} returns no rows, the
     * join finds nothing, and the update touches nothing. No early return, forgotten branch or
     * partial failure can apply a delta without logging it, or log one without applying it.
     *
     * <p>The passive ceiling is enforced by clamping the <em>result</em>, not by gating on the
     * prior value. Gating leaks: from 70 the steps are 72, 74, 76, 78, and at 78 a
     * {@code confidence < 79} guard still passes, so the next credit lands on 80 and the account
     * is trusted purely by waiting. The guard is kept anyway for a different job — an event it
     * skips is never logged, so it can still pay out if the account later falls back.
     *
     * @param sourceType one of {@code challenge}, {@code manager}, {@code review}
     * @param sourceId   identity of that thing, so the same cause is never counted twice
     */
    public Future<Void> apply(UUID userId, String reason, int delta,
                              String sourceType, String sourceId) {
        return db.preparedQuery("""
                WITH eligible AS (
                    SELECT id FROM users
                    WHERE id = $1
                      AND ($2 <> 'review_stood_30d' OR confidence < 79)
                ),
                logged AS (
                    INSERT INTO user_confidence_events
                        (user_id, delta, reason, source_type, source_id)
                    SELECT $1, $3, $2, $4, $5 FROM eligible
                    ON CONFLICT (user_id, reason, source_type, source_id) DO NOTHING
                    RETURNING user_id, delta, reason
                )
                UPDATE users u
                SET confidence = GREATEST(0, LEAST(
                        CASE WHEN l.reason = 'review_stood_30d' THEN 79 ELSE 100 END,
                        u.confidence + l.delta))
                FROM logged l
                WHERE u.id = l.user_id
                """)
            .execute(Tuple.of(userId, reason, delta, sourceType, sourceId))
            .mapEmpty();
    }

    public Future<Integer> current(UUID userId) {
        return db.preparedQuery("SELECT confidence FROM users WHERE id = $1")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().hasNext()
                ? rows.iterator().next().getInteger("confidence")
                : STARTING_CONFIDENCE);
    }

    /** The ledger behind one account's score, newest first. For admin surfaces only. */
    public Future<RowSet<Row>> history(UUID userId) {
        return db.preparedQuery(
                "SELECT delta, reason, source_type, source_id, created_at "
                + "FROM user_confidence_events WHERE user_id = $1 "
                + "ORDER BY created_at DESC LIMIT 50")
            .execute(Tuple.of(userId));
    }

    /**
     * Reviews that have been continuously live for 30 days and have not yet been credited.
     *
     * <p>Not {@code created_at >= 30 days}: a rating that sat held for twenty-nine days, got
     * approved, and earned standing the following morning would be rewarded for exactly the
     * behaviour this feature exists to slow down. {@code live_since} is reset whenever a rating
     * re-enters the live state, which is what makes "continuously" true rather than aspirational.
     */
    public Future<RowSet<Row>> findReviewsDueStandingCredit(int limit) {
        return db.preparedQuery("""
                SELECT r.id, r.user_id
                FROM published_reviews r
                JOIN users u ON u.id = r.user_id
                WHERE r.user_id IS NOT NULL
                  AND r.live_since <= now() - INTERVAL '30 days'
                  AND u.confidence < 79
                  AND NOT EXISTS (
                      SELECT 1 FROM user_confidence_events e
                      WHERE e.user_id = r.user_id
                        AND e.reason = 'review_stood_30d'
                        AND e.source_type = 'review'
                        AND e.source_id = r.id::text
                  )
                LIMIT $1
                """)
            .execute(Tuple.of(limit));
    }
}
