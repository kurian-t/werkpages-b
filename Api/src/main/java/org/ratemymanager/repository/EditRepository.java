package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for the {@code manager_edits} table.
 */
public class EditRepository {

    private final SqlClient db;

    public EditRepository(SqlClient db) {
        this.db = db;
    }

    public Future<Long> countSubmittedTodayByUser(UUID userId) {
        return db.preparedQuery("SELECT COUNT(*) FROM manager_edits WHERE proposed_by = $1 AND created_at >= current_date")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getLong(0));
    }

    public Future<Row> upsert(long managerId, UUID proposedBy, String newCompany,
                               String newTitle, String newStatus, String newCountry,
                               String newLinkedinUrl,
                               OffsetDateTime newStartDate, OffsetDateTime newEndDate) {
        return db.preparedQuery("""
                INSERT INTO manager_edits(manager_id, proposed_by, new_company, new_title, new_status, new_country, new_linkedin_url, new_start_date, new_end_date)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                ON CONFLICT (manager_id, proposed_by) WHERE status = 'pending'
                DO UPDATE SET new_company      = EXCLUDED.new_company,
                              new_title        = EXCLUDED.new_title,
                              new_status       = EXCLUDED.new_status,
                              new_country      = EXCLUDED.new_country,
                              new_linkedin_url = EXCLUDED.new_linkedin_url,
                              new_start_date   = EXCLUDED.new_start_date,
                              new_end_date     = EXCLUDED.new_end_date,
                              created_at       = now()
                RETURNING id, created_at
                """)
            .execute(Tuple.of(managerId, proposedBy, newCompany, newTitle, newStatus, newCountry, newLinkedinUrl, newStartDate, newEndDate))
            .map(rows -> rows.iterator().next());
    }

    public Future<RowSet<Row>> findPendingByManagerAndUser(long managerId, UUID userId) {
        return db.preparedQuery("""
                SELECT id, new_company, new_title, new_status, new_country, new_linkedin_url, new_start_date, new_end_date, created_at
                FROM manager_edits
                WHERE manager_id = $1 AND proposed_by = $2 AND status = 'pending'
                ORDER BY created_at DESC LIMIT 1
                """)
            .execute(Tuple.of(managerId, userId));
    }

    public Future<RowSet<Row>> findPendingForAdmin(int limit, int offset) {
        return db.preparedQuery("""
                SELECT pe.id, pe.manager_id, m.name AS manager_name,
                       m.company AS current_company, m.title AS current_title,
                       u.username AS requested_by,
                       pe.new_company, pe.new_title, pe.new_status, pe.new_country,
                       pe.new_linkedin_url, pe.status, pe.created_at
                FROM manager_edits pe
                JOIN managers m ON m.id = pe.manager_id
                JOIN users u ON u.id = pe.proposed_by
                WHERE pe.status = 'pending'
                ORDER BY pe.created_at ASC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    public Future<Optional<Row>> findByIdWithManager(UUID editId) {
        return db.preparedQuery("""
                SELECT pe.id, pe.manager_id, pe.new_company, pe.new_title, pe.new_status,
                       pe.new_country, pe.new_linkedin_url, pe.status, pe.proposed_by,
                       m.company AS current_company, m.title AS current_title,
                       m.company_id AS current_company_id,
                       m.created_at AS manager_created_at, m.name AS manager_name
                FROM manager_edits pe
                JOIN managers m ON m.id = pe.manager_id
                WHERE pe.id = $1
                """)
            .execute(Tuple.of(editId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Fetches a pending edit with just the fields needed for rejection notification. */
    public Future<Optional<Row>> findPendingById(UUID editId) {
        return db.preparedQuery("""
                SELECT pe.id, pe.proposed_by, pe.status, m.name AS manager_name
                FROM manager_edits pe
                JOIN managers m ON m.id = pe.manager_id
                WHERE pe.id = $1
                """)
            .execute(Tuple.of(editId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Void> approve(UUID editId, UUID adminId, OffsetDateTime reviewedAt) {
        return db.preparedQuery("UPDATE manager_edits SET status = 'approved', reviewed_at = $1, reviewed_by = $2 WHERE id = $3")
            .execute(Tuple.of(reviewedAt, adminId, editId))
            .mapEmpty();
    }

    public Future<Optional<Row>> reject(UUID editId, UUID adminId, OffsetDateTime reviewedAt) {
        return db.preparedQuery("UPDATE manager_edits SET status = 'rejected', reviewed_at = $1, reviewed_by = $2 WHERE id = $3 RETURNING id")
            .execute(Tuple.of(reviewedAt, adminId, editId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }
}
