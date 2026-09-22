package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * Partial submissions, kept for an admin to read.
 *
 * <p>Only for the forms that have no domain row to create when somebody walks away. The manager
 * forms capture into real {@code pending_approval} rows, which an admin already reviews; duplicating
 * them here would put the same submission in two queues.
 */
public class CapturedDraftRepository {

    public static final String COMPANY_RATING = "company_rating";
    public static final String INTERVIEW      = "interview";

    private final SqlClient db;

    public CapturedDraftRepository(SqlClient db) {
        this.db = db;
    }

    /**
     * Records what a form held.
     *
     * <p>A draft that arrives twice — somebody pressing submit, signing in, and being bounced again
     * — replaces the earlier one rather than queueing a second copy of the same work.
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
     * Removes the draft a finished submission came from.
     *
     * <p>Called on the real submit. Without it an admin spends their time reading drafts whose
     * authors came back a minute later and completed the form.
     */
    public Future<Void> clear(SqlClient conn, UUID draftToken) {
        if (draftToken == null) return Future.succeededFuture();
        return conn.preparedQuery("DELETE FROM captured_drafts WHERE draft_token = $1")
            .execute(Tuple.of(draftToken))
            .mapEmpty();
    }

    /** The queue, newest first. Reviewed drafts are kept but not shown. */
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

    /** Marks one draft as dealt with. Kept rather than deleted, so the queue can be audited. */
    public Future<Boolean> markReviewed(long id) {
        return db.preparedQuery("UPDATE captured_drafts SET reviewed_at = now() WHERE id = $1 AND reviewed_at IS NULL")
            .execute(Tuple.of(id))
            .map(rs -> rs.rowCount() > 0);
    }
}
