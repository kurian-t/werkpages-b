package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * Data-access layer for the {@code notifications} table.
 */
public class NotificationRepository {

    private final SqlClient db;

    public NotificationRepository(SqlClient db) {
        this.db = db;
    }

    public Future<RowSet<Row>> findByUser(UUID userId) {
        return db.preparedQuery(
                "SELECT id, type, title, message, read, created_at " +
                "FROM notifications WHERE user_id = $1 ORDER BY created_at DESC LIMIT 50")
            .execute(Tuple.of(userId));
    }

    public Future<Long> countUnread(UUID userId) {
        return db.preparedQuery("SELECT COUNT(*) AS cnt FROM notifications WHERE user_id = $1 AND read = FALSE")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    public Future<Void> markAllRead(UUID userId) {
        return db.preparedQuery("UPDATE notifications SET read = TRUE WHERE user_id = $1 AND read = FALSE")
            .execute(Tuple.of(userId))
            .mapEmpty();
    }

    public Future<Void> markRead(UUID notifId, UUID userId) {
        return db.preparedQuery("UPDATE notifications SET read = TRUE WHERE id = $1 AND user_id = $2")
            .execute(Tuple.of(notifId, userId))
            .mapEmpty();
    }

    /** Fire-and-forget: inserts a notification without blocking the caller. */
    public void sendAsync(UUID userId, String type, String title, String message) {
        db.preparedQuery("INSERT INTO notifications (user_id, type, title, message) VALUES ($1, $2, $3, $4)")
            .execute(Tuple.of(userId, type, title, message), ar -> { /* fire-and-forget */ });
    }
}
