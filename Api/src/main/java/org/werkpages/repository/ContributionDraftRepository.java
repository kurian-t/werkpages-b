package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A signed-in contributor's unfinished rating, private to them until they submit it.
 *
 * <h2>Why this is not CapturedDraftRepository</h2>
 *
 * <p>{@code captured_drafts} exists so an ABANDONED ANONYMOUS contribution reaches moderation, and
 * the admin queue reads it. This exists so a person can resume their own work on another device,
 * and moderation must never see it. Two purposes, two readers, two lifecycles, two erasure rules,
 * so two tables - and because they are separate tables, "moderation never sees an unfinished
 * signed-in rating" is structural rather than a predicate every future query has to remember.
 *
 * <h2>Ownership is never a parameter the caller chooses</h2>
 *
 * <p>Every method here takes the owner and filters on it. The owner comes from the authenticated
 * session at the handler, never from a body field or a path segment, and {@code owner_id} is
 * immutable in the database. A draft token authorizes nothing: {@code capture_token} is carried so
 * a later submission can resolve an anonymous publication (the V96 rule) and is never a read
 * credential.
 */
public class ContributionDraftRepository {

    private final Pool db;

    public ContributionDraftRepository(Pool db) { this.db = db; }

    public SqlClient client() { return db; }

    /** What an upsert did, which the API turns into 200 or 409. */
    public enum Outcome { ACCEPTED, REPLAY, CONFLICT }

    /**
     * The result of one write attempt.
     *
     * @param outcome what happened
     * @param id      the draft, in every case
     * @param version the version now stored
     * @param payload only on CONFLICT, so the client can offer the newer answers
     */
    public record WriteResult(Outcome outcome, UUID id, long version, JsonObject payload) {}

    /**
     * Save a draft, idempotently, under optimistic concurrency.
     *
     * <h2>The three-way resolution, and why it is a transaction rather than one clever statement</h2>
     *
     * <ol>
     *   <li><b>{@code writeId} equals the stored one</b> - a REPLAY. The write already committed
     *       and its acknowledgement was lost, which on an unload save is not a rare race but the
     *       normal case: a {@code keepalive} request can succeed after the page is gone. Answered
     *       from the stored version, and nothing is written.
     *   <li><b>{@code baseVersion} equals the stored version</b> - ACCEPTED. The version advances
     *       by exactly one, enforced again by a trigger.
     *   <li><b>neither</b> - CONFLICT. Another device is ahead. Nothing is written and the caller
     *       is handed the server's answers so a person can choose, because silently preferring
     *       either side is how work disappears.
     * </ol>
     *
     * <p>{@code SELECT ... FOR UPDATE} first, so two devices writing at the same moment cannot
     * both read version 7 and both believe they advanced it. Expressing this as a single
     * {@code INSERT ... ON CONFLICT} would be shorter and could not report which of the three
     * happened, and the difference between a replay and a conflict is the whole point.
     *
     * <p>{@code captureToken} is applied on creation only. The trigger refuses any later change,
     * so a draft cannot be re-pointed at a different anonymous capture.
     */
    public Future<WriteResult> save(UUID ownerId, String subjectType, long subjectId, short ordinal,
                                    JsonObject payload, Long baseVersion, UUID writeId,
                                    UUID captureToken) {
        return db.withTransaction(conn -> conn.preparedQuery("""
                    SELECT id, version, write_id, payload
                      FROM contribution_drafts
                     WHERE owner_id = $1 AND subject_type = $2 AND subject_id = $3 AND ordinal = $4
                     FOR UPDATE
                    """)
                .execute(Tuple.of(ownerId, subjectType, subjectId, ordinal))
                .compose(rs -> {
                    if (!rs.iterator().hasNext()) {
                        return conn.preparedQuery("""
                                INSERT INTO contribution_drafts
                                    (owner_id, subject_type, subject_id, ordinal, payload,
                                     write_id, capture_token)
                                VALUES ($1, $2, $3, $4, $5, $6, $7)
                                RETURNING id, version
                                """)
                            .execute(Tuple.of(ownerId, subjectType, subjectId, ordinal, payload,
                                              writeId, captureToken))
                            .map(ins -> {
                                Row r = ins.iterator().next();
                                return new WriteResult(Outcome.ACCEPTED, r.getUUID("id"),
                                                       r.getLong("version"), null);
                            });
                    }
                    Row row = rs.iterator().next();
                    UUID id = row.getUUID("id");
                    long stored = row.getLong("version");

                    if (writeId != null && writeId.equals(row.getUUID("write_id"))) {
                        return Future.succeededFuture(
                            new WriteResult(Outcome.REPLAY, id, stored, null));
                    }
                    if (baseVersion == null || baseVersion != stored) {
                        return Future.succeededFuture(new WriteResult(
                            Outcome.CONFLICT, id, stored, row.getJsonObject("payload")));
                    }
                    return conn.preparedQuery("""
                            UPDATE contribution_drafts
                               SET payload = $1, version = version + 1, write_id = $2
                             WHERE id = $3
                            RETURNING version
                            """)
                        .execute(Tuple.of(payload, writeId, id))
                        .map(upd -> new WriteResult(Outcome.ACCEPTED, id,
                                                    upd.iterator().next().getLong("version"), null));
                }));
    }

    /** The contributor's own unfinished ratings, newest first. Never anybody else's. */
    public Future<List<Row>> listForOwner(UUID ownerId) {
        return db.preparedQuery("""
                SELECT id, subject_type, subject_id, ordinal, payload, version, capture_token,
                       created_at, updated_at
                  FROM contribution_drafts
                 WHERE owner_id = $1
                 ORDER BY updated_at DESC
                """)
            .execute(Tuple.of(ownerId))
            .map(ContributionDraftRepository::rows);
    }

    /**
     * One of the contributor's own drafts.
     *
     * <p>The owner is part of the predicate rather than checked afterwards, so a draft belonging
     * to somebody else is indistinguishable from one that does not exist. That is deliberate: a
     * 404 that differs from a 403 tells a stranger which draft ids are real.
     */
    public Future<Optional<Row>> findForOwner(UUID ownerId, UUID id) {
        return db.preparedQuery("""
                SELECT id, subject_type, subject_id, ordinal, payload, version, capture_token,
                       created_at, updated_at
                  FROM contribution_drafts
                 WHERE owner_id = $1 AND id = $2
                """)
            .execute(Tuple.of(ownerId, id))
            .map(rs -> rs.iterator().hasNext()
                ? Optional.of(rs.iterator().next())
                : Optional.empty());
    }

    /** The contributor discarding their own. True when a row was theirs and is now gone. */
    public Future<Boolean> deleteForOwner(UUID ownerId, UUID id) {
        return db.preparedQuery("DELETE FROM contribution_drafts WHERE owner_id = $1 AND id = $2")
            .execute(Tuple.of(ownerId, id))
            .map(rs -> rs.rowCount() > 0);
    }

    /**
     * Removes the draft a submission has just replaced, in the submission's own transaction.
     *
     * <p>Takes a connection because it only ever runs beside the write that supersedes it: a draft
     * that outlived its own published rating would offer somebody their finished work back as
     * unfinished, and resuming it would look like their rating had reverted.
     */
    public Future<Void> deleteOnSubmission(SqlClient conn, UUID ownerId, String subjectType,
                                           long subjectId) {
        return conn.preparedQuery("""
                DELETE FROM contribution_drafts
                 WHERE owner_id = $1 AND subject_type = $2 AND subject_id = $3
                """)
            .execute(Tuple.of(ownerId, subjectType, subjectId))
            .mapEmpty();
    }

    /**
     * The retention sweep: drafts untouched for longer than the retention period.
     *
     * <p>An unfinished opinion is identifiable, so it is not kept indefinitely. The period is
     * disclosed to the contributor and returned with each draft, so expiry is never a surprise.
     */
    public Future<Integer> deleteStale(int retentionDays) {
        return db.preparedQuery(
                "DELETE FROM contribution_drafts WHERE updated_at < now() - ($1 || ' days')::interval")
            .execute(Tuple.of(String.valueOf(retentionDays)))
            .map(RowSet::rowCount);
    }

    private static List<Row> rows(RowSet<Row> rs) {
        List<Row> out = new ArrayList<>();
        rs.forEach(out::add);
        return out;
    }
}
