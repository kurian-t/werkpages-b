package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for the {@code users} and {@code banned_users} tables.
 * All methods return {@link Future} and perform no business logic.
 */
public class UserRepository {

    private final SqlClient db;

    public UserRepository(SqlClient db) {
        this.db = db;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Full profile including ban status, used after JWT authentication. */
    public Future<Optional<Row>> findByAuth0IdWithBan(String auth0Id) {
        return db.preparedQuery("""
                SELECT u.id, u.auth0_id, u.email, u.username, u.first_name, u.last_name,
                       u.role, u.created_at, (b.id IS NOT NULL) AS is_banned
                FROM users u
                LEFT JOIN banned_users b ON b.user_id = u.id
                WHERE u.auth0_id = $1
                """)
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /** Minimal lookup — just id. */
    public Future<Optional<UUID>> findIdByAuth0Id(String auth0Id) {
        return db.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next().getUUID("id"))
                : Optional.empty());
    }

    /** Includes ban check — used for sign-in response. */
    public Future<Optional<Row>> findByAuth0IdForSignin(String auth0Id) {
        return db.preparedQuery("""
                SELECT u.email, u.username, u.first_name, u.last_name, u.role,
                       (b.id IS NOT NULL) AS is_banned
                FROM users u
                LEFT JOIN banned_users b ON b.user_id = u.id
                WHERE u.auth0_id = $1
                """)
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<Boolean> isBanned(String auth0Id) {
        return db.preparedQuery("""
                SELECT EXISTS(
                    SELECT 1 FROM banned_users b
                    JOIN users u ON b.user_id = u.id
                    WHERE u.auth0_id = $1
                )
                """)
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().next().getBoolean(0));
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public Future<RowSet<Row>> create(String auth0Id, String email, String username,
                                      String firstName, String lastName) {
        return db.preparedQuery("""
                INSERT INTO users (auth0_id, email, username, first_name, last_name)
                VALUES ($1, $2, $3, $4, $5)
                """)
            .execute(Tuple.of(auth0Id, email, username, firstName, lastName));
    }

    /**
     * Anonymizes all reviews written by the user and then deletes the user record.
     * These two operations are chained but not transactional — acceptable given
     * the soft anonymize-then-delete pattern.
     */
    public Future<Void> deleteWithReviewAnonymization(UUID userId) {
        return db.preparedQuery("""
                UPDATE reviews
                SET author = 'Anonymous User', user_id = NULL, updated_at = now()
                WHERE user_id = $1
                """)
            .execute(Tuple.of(userId))
            .compose(ignored ->
                db.preparedQuery("DELETE FROM users WHERE id = $1")
                    .execute(Tuple.of(userId))
            )
            .mapEmpty();
    }

    // ── Admin user management ─────────────────────────────────────────────────

    public Future<Optional<Row>> findByIdForAdmin(UUID userId) {
        return db.preparedQuery("""
                SELECT u.id, u.username, u.first_name, u.last_name, u.role,
                       (SELECT b.id FROM banned_users b WHERE b.user_id = u.id LIMIT 1) AS ban_id
                FROM users u WHERE u.id = $1
                """)
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    public Future<String> findUsernameByAuth0Id(String auth0Id) {
        return db.preparedQuery("SELECT username FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rows -> rows.iterator().hasNext()
                ? rows.iterator().next().getString("username")
                : "admin");
    }

    public Future<RowSet<Row>> listNonAdminUsers(int limit, int offset) {
        return db.preparedQuery("""
                SELECT u.id, u.username, u.first_name, u.last_name, u.role,
                       (SELECT b.id FROM banned_users b WHERE b.user_id = u.id LIMIT 1) AS ban_id
                FROM users u
                WHERE u.role != 'admin'
                ORDER BY u.username ASC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }

    // ── Ban management ────────────────────────────────────────────────────────

    public Future<Boolean> banUser(UUID targetUserId, String reason, String bannedBy) {
        return db.preparedQuery("""
                INSERT INTO banned_users(user_id, reason, banned_by)
                VALUES ($1, $2, $3)
                ON CONFLICT (user_id) DO NOTHING
                RETURNING id
                """)
            .execute(Tuple.of(targetUserId, reason, bannedBy))
            .map(rows -> rows.iterator().hasNext()); // false = already banned
    }

    public Future<Boolean> unbanUser(UUID targetUserId) {
        return db.preparedQuery("DELETE FROM banned_users WHERE user_id = $1 RETURNING id")
            .execute(Tuple.of(targetUserId))
            .map(rows -> rows.iterator().hasNext()); // false = ban not found
    }

    public Future<RowSet<Row>> listBannedUsers(int limit, int offset) {
        return db.preparedQuery("""
                SELECT b.id, b.user_id, u.username, b.reason, b.banned_by, b.banned_at
                FROM banned_users b
                JOIN users u ON u.id = b.user_id
                ORDER BY b.banned_at DESC
                LIMIT $1 OFFSET $2
                """)
            .execute(Tuple.of(limit, offset));
    }
}
