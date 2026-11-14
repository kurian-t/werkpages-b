package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Opaque, hashed, product-scoped, revocable application sessions.
 *
 * <h2>What this replaces, and why a cookie was not a session</h2>
 *
 * <p>The session was the Auth0 access token, in an HttpOnly cookie, for 24 hours. Signing out set
 * {@code auth_token=; Max-Age=0}, which is an instruction to the browser and nothing more: a copy
 * of that cookie taken beforehand kept working for the rest of the 24 hours. So signing out did
 * not end a session, banning somebody did not end theirs, deleting an account did not end its
 * sessions, and an administrator had no way to end anybody's.
 *
 * <p>A session you cannot revoke is not a session. It is a bearer token with a long fuse.
 *
 * <h2>Three properties that are not negotiable</h2>
 *
 * <ol>
 *   <li><b>The id is never stored.</b> Only its SHA-256 is, and the hash is the primary key, so
 *       the per-request lookup is still a single primary-key probe. A dump of this table from a
 *       backup, a replica or an errant query then hands over no live sessions.
 *   <li><b>A session belongs to one product.</b> One shared database and one {@code users} table,
 *       so without the scope a session minted by RateMyManagers would authenticate to WerkPages.
 *       The predicate is in the SQL, never a check applied after a successful lookup, because a
 *       filter applied afterwards is one somebody later removes while tidying.
 *   <li><b>Revoking is immediate and whoever holds the cookie is refused.</b> Not at expiry, not
 *       on the next login: the next request.
 * </ol>
 *
 * <h2>Not yet wired in</h2>
 *
 * <p>Nothing calls this. It is the runtime half of V100, built and tested against the real schema
 * before any request depends on it, so the existing authentication path is untouched while it is
 * proven.
 */
public final class SessionRepository {

    /** The product this process is. A session minted here is valid only here. */
    public enum Product {
        RMM("rmm"), WERKPAGES("werkpages");

        private final String stored;
        Product(String stored) { this.stored = stored; }
        public String stored() { return stored; }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /**
     * Idle and absolute lifetimes.
     *
     * <p>Two clocks, because one is always wrong. The idle one is pushed forward on use so
     * ordinary people are not signed out weekly; the absolute one is not, so a session used
     * constantly still ends, which is what bounds the damage of a token taken and used quietly.
     */
    public static final Duration IDLE_LIFETIME = Duration.ofDays(14);
    public static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(90);

    private final Pool db;
    private final Product product;

    public SessionRepository(Pool db, Product product) {
        this.db = db;
        this.product = product;
    }

    /**
     * A new session. The returned string is the ONLY time the id exists in plaintext.
     *
     * <p>32 bytes from {@link SecureRandom}, base64url without padding: 43 characters, and no
     * guessable space for anybody to search. The caller puts it in the cookie and forgets it;
     * there is no way to recover it from the database afterwards, which is the point.
     */
    public Future<String> mint(UUID userId, String userAgent, byte[] addressHash) {
        String opaqueId = B64.encodeToString(randomBytes(32));
        return db.preparedQuery("""
                INSERT INTO app_sessions (id_hash, product, user_id, expires_at,
                                          absolute_expires_at, user_agent, address_hash)
                VALUES ($1, $2, $3,
                        now() + make_interval(days => $4),
                        now() + make_interval(days => $5),
                        $6, $7)
                """)
            /*
              make_interval with an integer, not a bound interval string.

              `now() + $4::interval` with "14 days" bound as text arrived as NULL and every insert
              failed on the NOT NULL constraint. make_interval takes the number of days as an
              integer, so there is no text to parse and no cast whose failure mode is a silent
              null.
            */
            .execute(Tuple.of(sha256(opaqueId), product.stored(), userId,
                              (int) IDLE_LIFETIME.toDays(),
                              (int) ABSOLUTE_LIFETIME.toDays())
                .addValue(userAgent).addValue(addressHash))
            .map(ignored -> opaqueId);
    }

    /**
     * The session this id names, if it is live and belongs to THIS product, touched on the way.
     *
     * <p>One statement, deliberately. A SELECT followed by an UPDATE would be a check-then-act
     * across two round trips, and the thing being checked is whether somebody is allowed in.
     *
     * <p>The idle clock is extended but never past the absolute one: {@code LEAST} is what stops a
     * session that is used every day from living for ever, which is the whole reason there are two
     * clocks rather than one.
     *
     * <p>Empty for every reason a session can be unusable - unknown, revoked, idle-expired,
     * absolutely expired, or belonging to the other product - and the caller cannot tell which.
     * Whoever is holding an unusable session can do nothing different about any of them.
     *
     * <p>The join to {@code users} is not merely a way to fetch {@code auth0_id} in one round
     * trip, although it is that too: every authorization decision in the product is made from the
     * Auth0 subject, so returning it here is what lets a session authenticate a request without a
     * second query. It also means <b>a session cannot validate once its user row is gone</b>. The
     * foreign key cascades on delete, so this is belt and braces, but it is the kind of belt worth
     * having on the one query that decides who somebody is.
     */
    public Future<Optional<Row>> touch(String opaqueId) {
        if (opaqueId == null || opaqueId.isBlank()) return Future.succeededFuture(Optional.empty());
        return db.preparedQuery("""
                UPDATE app_sessions s
                   SET last_seen_at = now(),
                       expires_at   = LEAST(now() + make_interval(days => $3),
                                            absolute_expires_at)
                  FROM users u
                 WHERE s.id_hash = $1
                   AND s.product = $2
                   AND s.revoked_at IS NULL
                   AND s.expires_at > now()
                   AND s.absolute_expires_at > now()
                   AND u.id = s.user_id
                RETURNING s.user_id, s.created_at, s.last_seen_at, s.expires_at,
                          s.absolute_expires_at, u.auth0_id
                """)
            .execute(Tuple.of(sha256(opaqueId), product.stored(),
                              (int) IDLE_LIFETIME.toDays()))
            .map(rows -> rows.iterator().hasNext()
                ? Optional.of(rows.iterator().next()) : Optional.empty());
    }

    /**
     * Ends one session, the person's own sign-out.
     *
     * <p>Idempotent: signing out twice, or with a cookie that names nothing, is a success. Signing
     * out is the one action that must never fail in a way that leaves somebody unsure whether it
     * worked.
     */
    public Future<Void> revoke(String opaqueId) {
        if (opaqueId == null || opaqueId.isBlank()) return Future.succeededFuture();
        return db.preparedQuery("""
                UPDATE app_sessions
                   SET revoked_at = now(), revoked_reason = 'signout'
                 WHERE id_hash = $1 AND product = $2 AND revoked_at IS NULL
                """)
            .execute(Tuple.of(sha256(opaqueId), product.stored()))
            .mapEmpty();
    }

    /**
     * Ends every session of one person, across BOTH products.
     *
     * <p>The one operation that deliberately crosses the product boundary, because a person
     * signing out everywhere means everywhere. It is also what a ban and an account deletion need.
     *
     * <p>{@code actorId} is null when the person did it themselves and set when somebody else did.
     * A revocation by anybody else writes an audit row naming them: a power to end other people's
     * sessions with no accounting is a power that gets used once and disputed for ever.
     */
    public Future<Integer> revokeAllForUser(UUID userId, String reason, UUID actorId) {
        return db.withTransaction(tx -> revokeAllForUser(tx, userId, reason, actorId));
    }

    /** {@link #revokeAllForUser(UUID, String, UUID)} inside a caller's transaction. */
    public Future<Integer> revokeAllForUser(SqlClient tx, UUID userId, String reason, UUID actorId) {
        return tx.preparedQuery("""
                UPDATE app_sessions
                   SET revoked_at = now(), revoked_reason = $2
                 WHERE user_id = $1 AND revoked_at IS NULL
                RETURNING id_hash
                """)
            .execute(Tuple.of(userId, reason))
            .compose(rows -> {
                int ended = rows.rowCount();
                if (actorId == null || ended == 0) return Future.succeededFuture(ended);
                // One audit row per session ended, each naming the actor and the reason.
                Future<?> chain = Future.succeededFuture();
                for (Row row : rows) {
                    byte[] hash = row.getBuffer("id_hash").getBytes();
                    chain = chain.compose(ignored -> tx.preparedQuery("""
                            INSERT INTO session_revocation_events (id_hash, user_id, revoked_by, reason)
                            VALUES ($1, $2, $3, $4)
                            """).execute(Tuple.of(hash, userId, actorId, reason)));
                }
                return chain.map(ignored -> ended);
            });
    }

    /** The live sessions of one person, for the list they are shown. Never the ids themselves. */
    public Future<RowSet<Row>> liveSessionsFor(UUID userId) {
        return liveSessionsFor(userId, null);
    }

    /**
     * The same listing, with the caller's own session marked.
     *
     * <p>Marked by COMPARING the hash, never by returning it. Somebody looking at their own
     * sessions needs to know which row is the browser they are reading it in - it is the one they
     * would not want to end - and that question is answerable without the page ever receiving
     * session material. Returning {@code id_hash} so a caller could compare it themselves would
     * hand script a value that, while not the session itself, is the exact string the database
     * keys on.
     *
     * @param currentOpaqueId the session presented by this request, or null when there is none
     */
    public Future<RowSet<Row>> liveSessionsFor(UUID userId, String currentOpaqueId) {
        byte[] current = (currentOpaqueId == null || currentOpaqueId.isBlank())
            ? null : sha256(currentOpaqueId);
        return db.preparedQuery("""
                SELECT product, created_at, last_seen_at, expires_at, user_agent,
                       ($2::bytea IS NOT NULL AND id_hash = $2) AS current
                  FROM app_sessions
                 WHERE user_id = $1 AND revoked_at IS NULL AND expires_at > now()
                 ORDER BY last_seen_at DESC
                """)
            .execute(Tuple.of(userId, current));
    }

    /**
     * Removes rows whose absolute life is over.
     *
     * <p>Housekeeping, not a security control: such a session is already refused by
     * {@link #touch}'s own predicate. This is what stops the table growing for ever.
     */
    public Future<Integer> sweepExpired() {
        return db.preparedQuery("DELETE FROM app_sessions WHERE absolute_expires_at <= now()")
            .execute().map(RowSet::rowCount);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * SHA-256, unsalted and unstretched, which is correct HERE and nowhere near a password.
     *
     * <p>The input is 32 bytes of CSPRNG output, so there is no guessable space for a dictionary to
     * search, and this runs on every authenticated request. bcrypt or Argon2 here would add
     * latency to every request and buy nothing.
     */
    /**
     * The caller's address, hashed, or null when there is none to hash.
     *
     * <p>Stored so that "sessions on this account" can show somebody where their account is being
     * used from without the product keeping a log of addresses. The salt comes from the same
     * configured secret the quota hashes use, under a DIFFERENT domain tag, so the session table
     * and the quota tables cannot be joined on the hash to follow one address between them.
     *
     * <p>Null rather than a constant for an unidentifiable caller: a shared bucket would make
     * every unattributable session look like one busy browser.
     */
    public static byte[] hashAddress(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(addressSalt().getBytes(StandardCharsets.UTF_8));
            digest.update("app-session-address".getBytes(StandardCharsets.UTF_8));
            return digest.digest(clientIp.trim().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String addressSalt() {
        String configured = System.getenv("GHOST_SLOT_SALT");
        return (configured == null || configured.isBlank()) ? "werkpages-ghost-slot" : configured;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JVM. If it is absent the platform is broken, and
            // continuing without a session hash would be worse than failing here.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
