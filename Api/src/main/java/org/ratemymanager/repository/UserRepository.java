package org.ratemymanager.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.ratemymanager.service.EncryptionService;

import java.util.Optional;
import java.util.UUID;

/**
 * Data-access layer for the {@code users} and {@code banned_users} tables.
 * All methods return {@link Future} and perform no business logic.
 *
 * When an {@link EncryptionService} is supplied, email, first_name, and last_name
 * are encrypted at rest. Callers that read those fields from a returned {@link Row}
 * must call {@link #decryptField} before using the value.
 */
public class UserRepository {

    private final SqlClient       db;
    private final EncryptionService enc; // nullable — null means no encryption (dev / tests)

    /** No-encryption constructor — used by integration tests. */
    public UserRepository(SqlClient db) {
        this(db, null);
    }

    public UserRepository(SqlClient db, EncryptionService enc) {
        this.db  = db;
        this.enc = enc;
    }

    // ── Encryption helpers ────────────────────────────────────────────────────

    /** Encrypts {@code s} if encryption is enabled, otherwise returns {@code s} unchanged. */
    private String e(String s) { return enc != null ? enc.encrypt(s) : s; }

    /** Decrypts {@code s} if encryption is enabled, otherwise returns {@code s} unchanged. */
    private String d(String s) { return enc != null ? enc.decrypt(s) : s; }

    /** HMAC for blind-index lookup; returns {@code s} as-is when encryption is disabled. */
    private String h(String s) { return enc != null ? enc.hmac(s) : s; }

    /**
     * Decrypts a field value read from a returned {@link Row}.
     * Call this on any email / first_name / last_name value obtained via
     * {@code row.getString(...)}, e.g. {@code userRepo.decryptField(row.getString("email"))}.
     */
    public String decryptField(String value) { return d(value); }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Full profile including ban status, used after JWT authentication. */
    public Future<Optional<Row>> findByAuth0IdWithBan(String auth0Id) {
        return db.preparedQuery("""
                SELECT u.id, u.auth0_id,
                       COALESCE(u.email_encrypted, u.email)           AS email,
                       u.username,
                       COALESCE(u.first_name_encrypted, u.first_name) AS first_name,
                       COALESCE(u.last_name_encrypted,  u.last_name)  AS last_name,
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
                SELECT COALESCE(u.email_encrypted, u.email)           AS email,
                       u.username,
                       COALESCE(u.first_name_encrypted, u.first_name) AS first_name,
                       COALESCE(u.last_name_encrypted,  u.last_name)  AS last_name,
                       u.role, (b.id IS NOT NULL) AS is_banned
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
                INSERT INTO users (auth0_id, email_encrypted, email_hash, username,
                                   first_name_encrypted, last_name_encrypted)
                VALUES ($1, $2, $3, $4, $5, $6)
                """)
            .execute(Tuple.of(auth0Id, e(email), h(email), username, e(firstName), e(lastName)));
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
                SELECT u.id, u.username,
                       COALESCE(u.first_name_encrypted, u.first_name) AS first_name,
                       COALESCE(u.last_name_encrypted,  u.last_name)  AS last_name,
                       u.role,
                       (SELECT b.id FROM banned_users b WHERE b.user_id = u.id LIMIT 1) AS ban_id
                FROM users u WHERE u.id = $1
                """)
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next())
                : Optional.empty());
    }

    /**
     * Returns the (decrypted) email for a given username, used during sign-in
     * to resolve a username to the email expected by Auth0.
     */
    public Future<Optional<String>> findEmailByUsername(String username) {
        return db.preparedQuery("""
                SELECT COALESCE(email_encrypted, email) AS email
                FROM users WHERE lower(username) = lower($1)
                """)
            .execute(Tuple.of(username))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(d(rows.iterator().next().getString("email")))
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
                SELECT u.id, u.username,
                       COALESCE(u.first_name_encrypted, u.first_name) AS first_name,
                       COALESCE(u.last_name_encrypted,  u.last_name)  AS last_name,
                       u.role,
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

    // ── Auto-create tracking ──────────────────────────────────────────────────

    /**
     * Atomically claims the one-time ghost-creation slot for this user.
     * Returns true if the slot was successfully claimed (row updated from FALSE to TRUE),
     * false if it was already claimed (by this request or a concurrent one).
     * Using UPDATE...WHERE...RETURNING eliminates the TOCTOU race between read and write.
     */
    public Future<Boolean> claimAutoCreatedManagerSlot(UUID userId) {
        return db.preparedQuery(
                "UPDATE users SET has_auto_created_manager = TRUE WHERE id = $1 AND has_auto_created_manager = FALSE RETURNING id")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().hasNext());
    }

    public Future<Void> resetAutoCreatedManagerSlot(UUID userId) {
        return db.preparedQuery("UPDATE users SET has_auto_created_manager = FALSE WHERE id = $1")
            .execute(Tuple.of(userId))
            .mapEmpty();
    }

    public Future<Boolean> hasContributed(UUID userId) {
        return db.preparedQuery(
                "SELECT EXISTS(SELECT 1 FROM reviews WHERE user_id = $1) AS contributed")
            .execute(Tuple.of(userId))
            .map(rows -> rows.iterator().next().getBoolean("contributed"));
    }
}
