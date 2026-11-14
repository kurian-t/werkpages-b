package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;
import java.util.UUID;

/**
 * The capture-attempt ledger: one row per attempt at the add-manager form.
 *
 * <p>It answers exactly one question - <em>has this form attempt already been seen</em> - which is
 * a question about requests, not about people or jobs. That is why it is its own table and not a
 * column on anything: a capture attempt and a captured draft are different records, and a
 * contribution can have one, both or neither.
 *
 * <p><b>What it replaced.</b> The submission used to find the capture its own attempt had left
 * behind by resemblance: same name, the captured company a prefix of the submitted one, no
 * submitter yet, under an hour old. That shape does describe the race, and it also describes a
 * namesake somebody else captured minutes earlier, who was then adopted into a stranger's
 * submission. The token identifies the attempt, so there is nothing left to guess.
 *
 * <p><b>Tokens are never cleared, and rows are never deleted.</b> The row's existence is the
 * consumption record. An earlier design cleared the token when the attempt resolved, so a late or
 * retried request matched nothing and the creation path produced a duplicate - through the
 * mechanism built to prevent duplicates.
 *
 * <h2>Why the claim is a single statement</h2>
 *
 * <p>{@code SELECT ... FOR UPDATE} was wrong here and the reason is worth keeping: a row that does
 * not exist cannot be locked. Two concurrent requests carrying one new token both read nothing,
 * neither holds a lock, both insert, and one fails on the primary key. The preliminary read gave
 * the appearance of serialisation and provided none.
 */
public class CaptureAttemptRepository {

    /** The form was submitted. The rating this attempt was for exists. */
    public static final String OUTCOME_SUBMITTED = "submitted";
    /** Nobody came back before the autosave window closed. */
    public static final String OUTCOME_ABANDONED = "abandoned";
    /** The draft was thrown away deliberately. */
    public static final String OUTCOME_DISCARDED = "discarded";
    /** The manager this attempt created was merged into another. */
    public static final String OUTCOME_MERGED    = "merged";

    private final SqlClient db;

    public CaptureAttemptRepository(SqlClient db) {
        this.db = db;
    }

    /**
     * Claims the attempt, creating the ledger row when this request is the first to present the
     * token.
     *
     * <p><b>Must run inside the same transaction as the manager insert that follows it.</b> Claim
     * the token first and a failure cannot leave an orphan manager; claim it in a transaction of
     * its own and a later failure leaves the token permanently consumed with nothing behind it,
     * which is precisely the defect this ledger was rewritten to avoid. If the transaction aborts,
     * neither the ledger row nor the manager exists, so the token is not spent and a retry is a
     * clean first attempt.
     *
     * <p>{@code DO UPDATE} rather than {@code DO NOTHING} so a concurrent loser blocks on the row
     * lock and then reads the winner's committed row, {@code manager_id} included, instead of
     * being handed an empty result it would have to interpret.
     *
     * @return the attempt row, with {@code created} true when this request is its creator
     */
    public Future<Row> claim(SqlClient conn, UUID token) {
        return (conn == null ? db : conn).preparedQuery("""
                INSERT INTO capture_attempts (token) VALUES ($1)
                ON CONFLICT (token) DO UPDATE SET token = capture_attempts.token
                RETURNING token, manager_id, resolved_at, outcome, (xmax = 0) AS created
                """)
            .execute(Tuple.of(token))
            .map(rows -> rows.iterator().next());
    }

    /**
     * Records which manager the attempt produced.
     *
     * <p>Runs in the creating transaction, after the insert, so the row never commits without it.
     */
    public Future<Void> link(SqlClient conn, UUID token, long managerId) {
        return (conn == null ? db : conn)
            .preparedQuery("UPDATE capture_attempts SET manager_id = $2 WHERE token = $1")
            .execute(Tuple.of(token, managerId))
            .mapEmpty();
    }

    /**
     * Reads an attempt without claiming one.
     *
     * <p>For deciding what a request that presents an already-known token should be told. It is
     * never the basis for creating anything: {@link #claim} is, because only the claim is atomic.
     */
    public Future<Optional<Row>> find(UUID token) {
        return db.preparedQuery("""
                SELECT token, manager_id, created_at, resolved_at, outcome
                FROM capture_attempts WHERE token = $1
                """)
            .execute(Tuple.of(token))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * Resolves the attempt, in the same transaction as the outcome it describes.
     *
     * <p>Guarded on {@code resolved_at IS NULL} so a late duplicate resolution cannot overwrite
     * the first outcome. Resolving separately from the outcome would consume the token
     * prematurely: a submission whose transaction then rolled back would leave the attempt spent
     * and its work unrecorded.
     */
    public Future<Void> resolve(SqlClient conn, UUID token, String outcome) {
        return (conn == null ? db : conn).preparedQuery("""
                UPDATE capture_attempts SET resolved_at = now(), outcome = $2
                 WHERE token = $1 AND resolved_at IS NULL
                """)
            .execute(Tuple.of(token, outcome))
            .mapEmpty();
    }
}
