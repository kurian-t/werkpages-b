package org.werkpages.integration;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.OAuthTransactionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V98: the server-side OAuth login transaction, and the two attacks it exists to stop.
 *
 * <h2>What was broken, and how it was observed</h2>
 *
 * <p>{@code POST /api/auth/callback} accepted {@code {code, redirectUri}} and exchanged the code
 * using the backend's Auth0 client secret, with nothing binding that code to the browser which
 * began the login. A grep of both products found no {@code code_challenge}, {@code code_verifier},
 * {@code pkce}, {@code S256} or {@code nonce} anywhere. Separately, {@code state} was minted in
 * the frontend and the frontend logged {@code "state mismatch - proceeding anyway"}, so it was
 * decorative. RFC 9700 requires code-injection protection for every authorization-code client.
 *
 * <h2>Why these assertions, and not simply "the happy path works"</h2>
 *
 * <p>Every test here is a refusal, because a security control is only ever observed by what it
 * REJECTS. A suite of happy paths passes just as well against a backend with the control removed,
 * which is exactly how this class of defect survives review.
 *
 * <p>The two attacks need two different controls and it is easy to believe one closes both:
 *
 * <ul>
 *   <li><b>Code injection</b> is closed by the verifier never leaving the server.
 *   <li><b>Login CSRF</b> is closed by {@code browser_hash}, and <b>not</b> by one-time
 *       consumption: an attacker who starts their own login and induces a victim's browser to
 *       finish it consumes the attacker's own row perfectly legitimately. That is the single most
 *       important assertion in this file ({@link #aDifferentBrowserCannotConsumeTheTransaction}).
 * </ul>
 */
@Testcontainers
class OAuthLoginTransactionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("oauth_login_transactions")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static OAuthTransactionRepository transactions;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();
        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(8));
        transactions = new OAuthTransactionRepository(pool);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) {
            pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void clean() {
        await(pool.query("TRUNCATE oauth_login_transactions").execute());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private long rowCount() {
        return await(pool.query("SELECT count(*) AS c FROM oauth_login_transactions").execute())
            .iterator().next().getLong("c");
    }

    // ── the happy path, stated once so the refusals mean something ────────────

    @Test
    void theStartingBrowserGetsItsVerifierBack() {
        String browser = OAuthTransactionRepository.randomToken();
        String state   = OAuthTransactionRepository.randomToken();
        await(transactions.start(state, browser, "the-verifier", "google-oauth2", "/search"));

        Optional<OAuthTransactionRepository.Consumed> consumed =
            await(transactions.consume(state, browser));

        assertTrue(consumed.isPresent(), "the browser that started the login must be able to finish it");
        assertEquals("the-verifier", consumed.get().codeVerifier());
        assertEquals("google-oauth2", consumed.get().connection());
        assertEquals("/search", consumed.get().returnTo());
    }

    // ── LOGIN CSRF: the assertion this whole design exists for ────────────────

    @Test
    void aDifferentBrowserCannotConsumeTheTransaction() {
        /*
          THE ATTACK, concretely.

          The attacker starts their own login and holds a valid state and a valid authorization
          code. They then induce the victim's browser to call the callback with them. If the
          lookup were by state alone, the attacker's row would be consumed, the exchange would
          succeed, and the VICTIM would be signed in as the ATTACKER - after which everything the
          victim contributes is attributed to the attacker's account.

          One-time consumption does not help here: the row is consumed exactly once, legitimately.
          Only the browser secret tells the two browsers apart.
        */
        String attackerBrowser = OAuthTransactionRepository.randomToken();
        String victimBrowser   = OAuthTransactionRepository.randomToken();
        String state           = OAuthTransactionRepository.randomToken();

        await(transactions.start(state, attackerBrowser, "attacker-verifier", "google-oauth2", "/"));

        assertTrue(await(transactions.consume(state, victimBrowser)).isEmpty(),
            "a valid state presented by a different browser must be refused");
    }

    @Test
    void arefusedAttemptDoesNotDestroyTheRealTransaction() {
        /*
          The browser hash is part of the WHERE clause, not a check after the delete.

          If the row were selected by state, deleted, and the browser compared afterwards, then
          anyone who learned a state could destroy somebody else's pending login at will. The
          legitimate browser would then be refused on its own callback. That is a denial of
          service with no authentication required, so this asserts the row SURVIVES a wrong-browser
          attempt and still works for its owner.
        */
        String owner  = OAuthTransactionRepository.randomToken();
        String state  = OAuthTransactionRepository.randomToken();
        await(transactions.start(state, owner, "v", "google-oauth2", "/"));

        await(transactions.consume(state, OAuthTransactionRepository.randomToken()));

        assertEquals(1, rowCount(), "a refused attempt must not consume the row");
        assertTrue(await(transactions.consume(state, owner)).isPresent(),
            "the real browser must still be able to complete its login");
    }

    // ── single use ────────────────────────────────────────────────────────────

    @Test
    void aTransactionCanOnlyBeConsumedOnce() {
        String browser = OAuthTransactionRepository.randomToken();
        String state   = OAuthTransactionRepository.randomToken();
        await(transactions.start(state, browser, "v", "google-oauth2", "/"));

        assertTrue(await(transactions.consume(state, browser)).isPresent());
        assertTrue(await(transactions.consume(state, browser)).isEmpty(),
            "replaying the same transaction must be refused");
        assertEquals(0, rowCount());
    }

    @Test
    void concurrentCallbacksProduceExactlyOneWinner() {
        /*
          Two callbacks for one transaction, in flight at once - a double-submit, a retried tab, or
          an attacker racing the victim.

          This is why consumption is a single DELETE ... RETURNING rather than a SELECT followed by
          a DELETE. Read-then-delete leaves a window in which both callers see the row and both
          proceed to exchange, and a window that small is exactly the one that never shows up in
          manual testing.
        */
        String browser = OAuthTransactionRepository.randomToken();
        String state   = OAuthTransactionRepository.randomToken();
        await(transactions.start(state, browser, "v", "google-oauth2", "/"));

        List<Future<Optional<OAuthTransactionRepository.Consumed>>> racers = new ArrayList<>();
        for (int i = 0; i < 8; i++) racers.add(transactions.consume(state, browser));
        await(Future.all(new ArrayList<>(racers)));

        long winners = racers.stream().filter(f -> f.result().isPresent()).count();
        assertEquals(1, winners, "exactly one concurrent callback may consume the transaction");
    }

    // ── expiry ────────────────────────────────────────────────────────────────

    @Test
    void anExpiredTransactionIsRefusedEvenByItsOwnBrowser() {
        String browser = OAuthTransactionRepository.randomToken();
        String state   = OAuthTransactionRepository.randomToken();
        await(transactions.start(state, browser, "v", "google-oauth2", "/"));
        await(pool.preparedQuery(
                "UPDATE oauth_login_transactions SET expires_at = now() - interval '1 second' "
              + "WHERE state = $1").execute(Tuple.of(state)));

        assertTrue(await(transactions.consume(state, browser)).isEmpty(),
            "expiry is enforced in the consuming statement, not only by the sweep");
    }

    @Test
    void theSweepRemovesOnlyExpiredRows() {
        String browser = OAuthTransactionRepository.randomToken();
        String live    = OAuthTransactionRepository.randomToken();
        String stale   = OAuthTransactionRepository.randomToken();
        await(transactions.start(live, browser, "v", "google-oauth2", "/"));
        await(transactions.start(stale, browser, "v", "google-oauth2", "/"));
        await(pool.preparedQuery(
                "UPDATE oauth_login_transactions SET expires_at = now() - interval '1 hour' "
              + "WHERE state = $1").execute(Tuple.of(stale)));

        assertEquals(1, await(transactions.deleteExpired()));
        assertEquals(1, rowCount());
        assertTrue(await(transactions.consume(live, browser)).isPresent(),
            "the sweep must not touch a live login");
    }

    // ── multi-tab ─────────────────────────────────────────────────────────────

    @Test
    void twoTabsOfOneBrowserCompleteIndependently() {
        /*
          The regression test for the flaw that sank the signed-cookie design.

          There, a second /start overwrote the first tab's cookie, so whichever tab the person
          finished second was refused. Here the cookie identifies the BROWSER and each tab gets
          its own row, so the order they finish in does not matter.
        */
        String browser = OAuthTransactionRepository.randomToken();
        String tabA    = OAuthTransactionRepository.randomToken();
        String tabB    = OAuthTransactionRepository.randomToken();
        await(transactions.start(tabA, browser, "verifier-a", "google-oauth2", "/a"));
        await(transactions.start(tabB, browser, "verifier-b", "windowslive", "/b"));

        // Deliberately finished in the opposite order to the one they were started in.
        Optional<OAuthTransactionRepository.Consumed> second = await(transactions.consume(tabB, browser));
        Optional<OAuthTransactionRepository.Consumed> first  = await(transactions.consume(tabA, browser));

        assertTrue(second.isPresent(), "the second tab must complete");
        assertTrue(first.isPresent(),  "and so must the first, in either order");
        assertEquals("verifier-b", second.get().codeVerifier());
        assertEquals("verifier-a", first.get().codeVerifier(), "the tabs must not share a verifier");
        assertEquals("/b", second.get().returnTo());
        assertEquals("/a", first.get().returnTo());
    }

    @Test
    void aBrowserWithLivePendingLoginsReusesItsSecret() {
        String browser = OAuthTransactionRepository.randomToken();
        assertFalse(await(transactions.hasLivePendingLogin(browser)),
            "an unknown browser has nothing pending");

        String state = OAuthTransactionRepository.randomToken();
        await(transactions.start(state, browser, "v", "google-oauth2", "/"));
        assertTrue(await(transactions.hasLivePendingLogin(browser)));

        await(transactions.consume(state, browser));
        assertFalse(await(transactions.hasLivePendingLogin(browser)),
            "once nothing is pending the secret may be rotated on the next start");
    }

    // ── what is stored ────────────────────────────────────────────────────────

    @Test
    void theBrowserSecretIsNeverStored() {
        /*
          A dump of this table must not yield anything a browser could present.

          The column holds sha256(secret), so reading the database gives an attacker a hash and no
          way back to the cookie value. This asserts the stored bytes are the hash and, explicitly,
          that the secret itself appears nowhere in the row.
        */
        String browser = "a-known-browser-secret";
        String state   = OAuthTransactionRepository.randomToken();
        await(transactions.start(state, browser, "v", "google-oauth2", "/"));

        byte[] stored = await(pool.preparedQuery(
                "SELECT browser_hash FROM oauth_login_transactions WHERE state = $1")
            .execute(Tuple.of(state))).iterator().next().getBuffer("browser_hash").getBytes();

        assertArrayEquals(OAuthTransactionRepository.browserHash(browser), stored);
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains(browser),
            "the raw secret must never appear in the stored row");
    }

    @Test
    void thePkceChallengeIsTheS256OfTheVerifier() throws Exception {
        /*
          The binding is only real if the challenge genuinely derives from the verifier. A
          challenge that merely EXISTS satisfies Auth0's parameter check and protects nothing, and
          "plain" would put the redeeming value on the authorize request in clear.
        */
        String verifier  = OAuthTransactionRepository.randomToken();
        String challenge = OAuthTransactionRepository.pkceChallengeFor(verifier);

        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        assertEquals(expected, challenge);
        assertNotEquals(verifier, challenge, "S256, never plain");
        assertTrue(verifier.length() >= 43 && verifier.length() <= 128,
            "RFC 7636 requires a verifier of 43 to 128 characters");
    }
}
