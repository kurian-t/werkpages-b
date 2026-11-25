package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for the {@code reports} table.
 */
public class ReportRepository {

    private final SqlClient db;

    public ReportRepository(SqlClient db) {
        this.db = db;
    }

    public Future<Boolean> managerExists(long managerId) {
        return db.preparedQuery("SELECT id FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rows -> rows.iterator().hasNext());
    }

    public Future<Boolean> alreadyReported(long managerId, UUID userId) {
        return db.preparedQuery("SELECT id FROM reports WHERE manager_id = $1 AND user_id = $2")
            .execute(Tuple.of(managerId, userId))
            .map(rows -> rows.iterator().hasNext());
    }

    public Future<Row> create(long managerId, UUID userId, String reason, String comment) {
        return db.preparedQuery("""
                INSERT INTO reports (manager_id, user_id, reason, comment)
                VALUES ($1, $2, $3, $4)
                RETURNING id, created_at
                """)
            .execute(Tuple.of(managerId, userId, reason, comment))
            .map(rows -> rows.iterator().next());
    }

    // ── the queue (V114) ─────────────────────────────────────────────────────

    /**
     * One page of reports in a given state, with everything needed to act on one.
     *
     * <p><b>The manager is joined, not just referenced.</b> A queue of manager ids cannot be
     * worked: an admin needs the name to recognise the profile and the slug to open it. The join is
     * inner because {@code manager_id} is {@code NOT NULL} and cascade-deleted, so a report without
     * a manager cannot exist.
     *
     * <p><b>Both user joins are LEFT.</b> {@code reports.user_id} and {@code resolved_by} are each
     * {@code ON DELETE SET NULL}, so a deleted account must leave the report standing rather than
     * removing it from the queue: the allegation about the profile is still worth looking at once
     * the account that made it is gone. An inner join here would silently hide exactly those rows.
     *
     * <p>The ordering differs by state on purpose. The open queue is oldest first, because the age
     * is the thing that matters - three reports where the oldest has waited five weeks is worse
     * than forty that turn over daily, and only one of those numbers shows it. A decided list is
     * newest decision first, which is what somebody reviewing recent calls wants. When the state is
     * {@code open} the {@code CASE} supplies the key and {@code resolved_at} is null throughout, so
     * each ordering is inert for the other's query.
     */
    public Future<RowSet<Row>> listByStatus(String status, int limit, int offset) {
        return db.preparedQuery("""
                SELECT r.id, r.manager_id, r.reason, r.comment, r.status, r.created_at,
                       r.resolved_at, r.resolution_note,
                       m.name AS manager_name, m.slug AS manager_slug,
                       u.username  AS reporter_username,
                       (r.user_id IS NOT NULL) AS reporter_known,
                       a.username  AS resolved_by_username
                  FROM reports r
                  JOIN managers m ON m.id = r.manager_id
                  LEFT JOIN users u ON u.id = r.user_id
                  LEFT JOIN users a ON a.id = r.resolved_by
                 WHERE r.status = $1
                 ORDER BY CASE WHEN $1 = 'open' THEN r.created_at END ASC NULLS LAST,
                          r.resolved_at DESC NULLS LAST
                 LIMIT $2 OFFSET $3
                """)
            .execute(Tuple.of(status, limit, offset));
    }

    public Future<Integer> countByStatus(String status) {
        return db.preparedQuery("SELECT count(*) AS n FROM reports WHERE status = $1")
            .execute(Tuple.of(status))
            .map(rows -> rows.iterator().next().getInteger("n"));
    }

    /** The current state of one report, or empty when there is no such report. */
    public Future<Optional<String>> findStatus(UUID reportId) {
        return db.preparedQuery("SELECT status FROM reports WHERE id = $1")
            .execute(Tuple.of(reportId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next().getString("status"))
                : Optional.empty());
    }

    /**
     * Decides one report, and only if it is still open.
     *
     * <p><b>{@code AND status = 'open'} is the whole concurrency story.</b> The service checks the
     * state first to tell a 404 from a 409, but two admins pressing at once would both pass that
     * check; the predicate here means the second UPDATE matches no row and the caller is told so
     * rather than overwriting the first decision and its author.
     *
     * @return true when this call is the one that decided it
     */
    public Future<Boolean> resolve(UUID reportId, String status, UUID adminId, String note) {
        return db.preparedQuery("""
                UPDATE reports
                   SET status          = $2,
                       resolved_at     = now(),
                       resolved_by     = $3,
                       resolution_note = $4
                 WHERE id = $1
                   AND status = 'open'
                """)
            .execute(Tuple.of(reportId, status, adminId, note))
            .map(rows -> rows.rowCount() == 1);
    }
}
