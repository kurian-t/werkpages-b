package org.werkpages.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * The pending-login store: one row per login a browser has started and not yet finished.
 *
 * <h2>What this is defending against</h2>
 *
 * <p>Two separate attacks, which need two separate controls, and it is easy to believe one closes
 * both.
 *
 * <ul>
 *   <li><b>Authorization-code injection.</b> A code belonging to one person, replayed from another
 *       browser, was exchanged and returned a session for the code's owner. PKCE closes this: the
 *       verifier lives here and never reaches any browser, so a code redeemed without it is
 *       refused by Auth0 itself.
 *   <li><b>Login CSRF.</b> The attacker starts their own login, obtains a valid state and code,
 *       and induces the victim's browser to complete it; the victim is signed in as the attacker
 *       and everything they then contribute is attributed to the attacker's account. PKCE does
 *       <b>not</b> close this, and neither does one-time consumption, because the attacker's own
 *       row is consumed perfectly legitimately. {@code browserHash} closes it.
 * </ul>
 *
 * <h2>Why consumption and binding happen in ONE statement</h2>
 *
 * <p>{@link #consume} is a single {@code DELETE ... WHERE state = $1 AND browser_hash = $2 AND
 * expires_at > now() RETURNING ...}. Reading the row and then deleting it would open a window in
 * which two concurrent callbacks both read it and both proceed. PostgreSQL locks the row for the
 * delete, so of two racing callbacks exactly one gets a row back and the other gets nothing.
 *
 * <p>There is no "find" method on this class on purpose. A caller that could look a transaction up
 * without consuming it would eventually be used to check one and act on it later, which is the
 * check-then-act this design exists to avoid.
 */
public class OAuthTransactionRepository {

    /**
     * Ten minutes. A login takes well under a minute; the window exists to bound how long a
     * captured cookie is worth anything, not to be generous.
     */
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final Pool db;

    public OAuthTransactionRepository(Pool db) {
        this.db = db;
    }

    /** What a consumed transaction hands back. Never the browser secret, which we only ever hash. */
    public record Consumed(String codeVerifier, String connection, String returnTo) {}

    /**
     * 32 bytes from {@link SecureRandom}, base64url without padding: 43 characters, which is inside
     * RFC 7636's 43-to-128 range for a {@code code_verifier} and is equally fine for the other two.
     */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    /**
     * {@code BASE64URL(SHA256(ASCII(verifier)))} — PKCE's S256 transformation.
     *
     * <p>Never {@code plain}. A plain challenge IS the verifier, so anyone who sees the authorize
     * request also holds the value that redeems the code and the control buys nothing.
     */
    public static String pkceChallengeFor(String codeVerifier) {
        return B64.encodeToString(sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /** The stored form of the browser secret. The secret itself never reaches the database. */
    public static byte[] browserHash(String browserSecret) {
        return sha256(browserSecret.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            // SHA-256 is mandated by the JRE spec. Failing loudly beats returning something that
            // looks like a hash and is not.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Records a started login. One row per tab, all sharing the browser's hash.
     *
     * <p>No upsert and no conflict handling: {@code state} is 32 random bytes, so a collision is
     * not a case to handle, it is a case that means the random source has failed and the insert
     * should fail loudly.
     */
    public Future<Void> start(String state, String browserSecret, String codeVerifier,
                              String connection, String returnTo) {
        return db.preparedQuery("""
                INSERT INTO oauth_login_transactions
                       (state, browser_hash, code_verifier, connection, return_to, expires_at)
                VALUES ($1, $2, $3, $4, $5, now() + make_interval(secs => $6))
                """)
            // make_interval with a numeric, NOT a text cast.
            //
            // `now() + $6::interval` with the seconds as a string bound as an untyped parameter
            // produced NULL, and the NOT NULL constraint refused the row. Caught by the
            // integration tests on their first run, which is the whole reason they exist: every
            // start would have failed closed in production and nobody could have signed in.
            .execute(Tuple.of(state, browserHash(browserSecret), codeVerifier,
                              connection, returnTo, (double) LIFETIME.toSeconds()))
            .mapEmpty();
    }

    /**
     * Consumes a transaction, or returns empty.
     *
     * <p>Empty covers every failure identically: no such state, the wrong browser, already used,
     * expired. The caller refuses all four the same way, because telling them apart would tell an
     * attacker which half of their forgery was wrong, and because the person can do nothing
     * different about any of them.
     *
     * <p><b>The browser hash is part of the WHERE clause, not a check afterwards.</b> Comparing it
     * in Java after selecting by state would still delete the row for a request that is about to
     * be refused, which hands an attacker a way to destroy other people's pending logins.
     */
    public Future<Optional<Consumed>> consume(String state, String browserSecret) {
        return db.preparedQuery("""
                DELETE FROM oauth_login_transactions
                 WHERE state        = $1
                   AND browser_hash = $2
                   AND expires_at   > now()
             RETURNING code_verifier, connection, return_to
                """)
            .execute(Tuple.of(state, browserHash(browserSecret)))
            .map(rows -> {
                if (rows.rowCount() == 0) return Optional.empty();
                Row row = rows.iterator().next();
                return Optional.of(new Consumed(
                    row.getString("code_verifier"),
                    row.getString("connection"),
                    row.getString("return_to")));
            });
    }

    /**
     * True when this browser still has at least one live pending login.
     *
     * <p>Used ONLY to decide whether {@code /api/auth/start} may reuse the cookie it already has
     * rather than minting a new secret. Minting a fresh one per start would overwrite the cookie
     * and strand every other tab's transaction, which is the multi-tab flaw that sank the earlier
     * stateless design.
     *
     * <p>It answers a yes/no about the caller's own browser and returns no transaction detail, so
     * it is not a way to look a transaction up without consuming it.
     */
    public Future<Boolean> hasLivePendingLogin(String browserSecret) {
        return db.preparedQuery("""
                SELECT 1 FROM oauth_login_transactions
                 WHERE browser_hash = $1 AND expires_at > now()
                 LIMIT 1
                """)
            .execute(Tuple.of(browserHash(browserSecret)))
            .map(rows -> rows.rowCount() > 0);
    }

    /**
     * Removes expired rows. Called from the maintenance sweep.
     *
     * <p>Expiry is already enforced in {@link #consume}'s WHERE clause, so this is housekeeping
     * rather than a control: an expired row is already unusable, it is simply taking up space.
     */
    public Future<Integer> deleteExpired() {
        return db.preparedQuery("DELETE FROM oauth_login_transactions WHERE expires_at <= now()")
            .execute()
            .map(rows -> rows.rowCount());
    }
}
