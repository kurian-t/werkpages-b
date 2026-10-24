package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;
import java.util.UUID;

/**
 * Partial submissions, kept for an admin to read and, when they are complete, to publish.
 *
 * <p>Only for the forms that have no domain row to create when somebody walks away. The manager
 * forms capture into real {@code pending_approval} rows, which an admin already reviews; duplicating
 * them here would put the same submission in two queues.
 *
 * <h2>Resolution</h2>
 *
 * <p>A draft leaves the queue in one of three ways, and which one it was matters enough to store:
 * {@code published} means an admin turned it into an anonymous rating, {@code dismissed} means
 * they read it and did not, and {@code superseded} means its work reached the corpus by another
 * route, because the author came back and submitted it or because a sibling draft holding the same
 * form was published. Rows are never deleted on resolution, so the queue can be audited.
 */
public class CapturedDraftRepository {

    public static final String COMPANY_RATING = "company_rating";
    public static final String INTERVIEW      = "interview";

    public static final String PUBLISHED  = "published";
    public static final String DISMISSED  = "dismissed";
    public static final String SUPERSEDED = "superseded";

    private final SqlClient db;

    public CapturedDraftRepository(SqlClient db) {
        this.db = db;
    }

    /**
     * Records what a form held.
     *
     * <p>A plain insert, so somebody bounced twice leaves two rows holding the same token. That is
     * deliberate: capturing happens while a person is already walking away, and an upsert keyed on
     * a client-generated token would make the courtesy conditional on a write succeeding against a
     * constraint. The consequence is handled where it matters rather than here, in
     * {@link #supersedeSiblings} and in the unique index on a published rating's {@code
     * draft_token}, so one form can only ever become one rating however many times it was kept.
     */
    public Future<Void> capture(String kind, Long companyId, Long managerId,
                                UUID userId, UUID draftToken, JsonObject payload) {
        return db.preparedQuery("""
                INSERT INTO captured_drafts (kind, company_id, manager_id, user_id, draft_token, payload)
                VALUES ($1, $2, $3, $4, $5, $6)
                """)
            .execute(Tuple.of(kind, companyId, managerId, userId, draftToken, payload))
            .mapEmpty();
    }

    /**
     * Takes the row lock on every draft holding this token, so two writers cannot act on one form.
     *
     * <p><b>This is the whole of the concurrency rule.</b> An admin publishing a draft and its
     * author returning to submit it properly are two transactions that both decide what happens to
     * the same captured form, and nothing else they touch is shared: the admin writes an anonymous
     * rating, the author writes their own, and each then removes what the other would have left
     * behind. Without a common lock both can read "no publication exists yet" and both can write
     * one.
     *
     * <p>A null token cannot be coordinated and does not need to be: the author's return finds a
     * published row by token, so a draft that never carried one can never be matched to a
     * submission, and there is no pair of writers to serialise.
     *
     * <p>{@code FOR UPDATE} on a SELECT that may match nothing is still correct. It locks whatever
     * is there now, which is what both paths read before deciding.
     */
    public Future<Void> lockByToken(SqlClient conn, UUID draftToken) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery("SELECT id FROM captured_drafts WHERE draft_token = $1 FOR UPDATE")
            .execute(Tuple.of(draftToken))
            .mapEmpty();
    }

    /**
     * Loads one draft for publication, holding its row lock until the transaction ends.
     *
     * <p>Empty means no such draft. A draft that is already resolved comes back anyway, so the
     * caller can refuse it with a reason rather than a 404 it cannot explain.
     */
    public Future<Optional<Row>> findForPublication(SqlClient conn, long id) {
        return conn.preparedQuery("""
                SELECT id, kind, company_id, manager_id, user_id, draft_token, payload,
                       reviewed_at, resolution
                  FROM captured_drafts
                 WHERE id = $1
                 FOR UPDATE
                """)
            .execute(Tuple.of(id))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty());
    }

    /**
     * Records what one draft became.
     *
     * <p>Guarded on {@code reviewed_at IS NULL}, so a draft resolved by somebody else between the
     * read and the write reports false rather than having its outcome overwritten. The caller is
     * inside a transaction holding this row's lock, which makes that a backstop rather than the
     * protection, but a resolution that can be silently replaced is not an audit trail.
     *
     * @param resolvedId the rating it became, or null when it became nothing
     */
    public Future<Boolean> resolve(SqlClient conn, long id, String resolution,
                                   UUID resolvedId, UUID resolvedBy) {
        return conn.preparedQuery("""
                UPDATE captured_drafts
                   SET reviewed_at = now(), resolution = $2, resolved_id = $3, resolved_by = $4
                 WHERE id = $1 AND reviewed_at IS NULL
                """)
            .execute(Tuple.of(id, resolution, resolvedId, resolvedBy))
            .map(rs -> rs.rowCount() > 0);
    }

    /**
     * Removes the drafts a finished submission came from, and only the untouched ones.
     *
     * <p>Called on the real submit. Without it an admin spends their time reading drafts whose
     * authors came back a minute later and completed the form.
     *
     * <p><b>{@code reviewed_at IS NULL} is the part that matters.</b> This used to delete every row
     * holding the token, which was harmless while the only outcome was "an admin read it" and is
     * not any more: a draft an admin PUBLISHED carries the record of what it became and who
     * decided, and the author coming back afterwards would have destroyed exactly the row that
     * explains where an anonymous rating on the page came from.
     */
    public Future<Void> clear(SqlClient conn, UUID draftToken) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery(
                "DELETE FROM captured_drafts WHERE draft_token = $1 AND reviewed_at IS NULL")
            .execute(Tuple.of(draftToken))
            .mapEmpty();
    }

    /**
     * Retires the other unresolved drafts of a form that has just been published.
     *
     * <p>The capture path inserts a fresh row every time somebody is bounced, so one form can hold
     * several drafts. Without this they stay in the queue, and the next attempt to publish one is
     * refused by a unique index rather than by anything an admin can act on.
     *
     * <p>Skips the row being published, which the caller resolves itself, and skips anything
     * already resolved, so an earlier decision is never rewritten.
     */
    public Future<Void> supersedeSiblings(SqlClient conn, UUID draftToken, long publishedId,
                                          UUID resolvedId) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery("""
                UPDATE captured_drafts
                   SET reviewed_at = now(), resolution = 'superseded', resolved_id = $3
                 WHERE draft_token = $1 AND id <> $2 AND reviewed_at IS NULL
                """)
            .execute(Tuple.of(draftToken, publishedId, resolvedId))
            .mapEmpty();
    }

    /**
     * Points a published draft at the rating that now represents it.
     *
     * <p>For the one sequence where both halves of this feature fire: an admin publishes a draft,
     * and its author then returns, signs in and submits the same form. The anonymous publication
     * is removed in favour of theirs, which would leave the draft's trail naming a rating that no
     * longer exists. It was still published, and this is what it is now, so the outcome stays
     * {@code published} and only the id moves.
     */
    public Future<Void> repointPublished(SqlClient conn, UUID draftToken, UUID resolvedId) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery("""
                UPDATE captured_drafts SET resolved_id = $2
                 WHERE draft_token = $1 AND resolution = 'published'
                """)
            .execute(Tuple.of(draftToken, resolvedId))
            .mapEmpty();
    }

    /** The queue, newest first. Resolved drafts are kept but not shown. */
    public Future<RowSet<Row>> findUnreviewed(int limit, int offset) {
        return db.preparedQuery("""
                SELECT d.id, d.kind, d.payload, d.created_at,
                       d.company_id, c.name AS company_name, c.slug AS company_slug,
                       d.manager_id, m.name AS manager_name,
                       u.username AS author_username
                  FROM captured_drafts d
                  LEFT JOIN companies c ON c.id = d.company_id
                  LEFT JOIN managers  m ON m.id = d.manager_id
                  LEFT JOIN users     u ON u.id = d.user_id
                 WHERE d.reviewed_at IS NULL
                 ORDER BY d.created_at DESC
                 LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    public Future<Long> countUnreviewed() {
        return db.preparedQuery("SELECT count(*) AS c FROM captured_drafts WHERE reviewed_at IS NULL")
            .execute()
            .map(rs -> rs.iterator().next().getLong("c"));
    }

    /**
     * Marks one draft read and not published. Kept rather than deleted, so the queue can be audited.
     *
     * <p>Records who decided. A draft holding a complete rating that an admin chose not to publish
     * is a judgement call, and the trail should say whose.
     */
    public Future<Boolean> dismiss(long id, UUID adminUserId) {
        return db.preparedQuery("""
                UPDATE captured_drafts
                   SET reviewed_at = now(), resolution = 'dismissed', resolved_by = $2
                 WHERE id = $1 AND reviewed_at IS NULL
                """)
            .execute(Tuple.of(id, adminUserId))
            .map(rs -> rs.rowCount() > 0);
    }
}
