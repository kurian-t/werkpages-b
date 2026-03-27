package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

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
}
