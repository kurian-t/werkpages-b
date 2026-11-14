package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.SessionRepository;
import org.werkpages.repository.SessionRepository.Product;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SESSIONS THAT CAN ACTUALLY BE ENDED.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The session was the Auth0 access token in a cookie for 24 hours, and `handleSignout` cleared
 * the cookie - an instruction to the browser and nothing more. A copy taken beforehand kept
 * working. Signing out did not end a session, a ban did not, deleting an account did not, and an
 * administrator could not end anybody's.
 *
 * <p>Every test here is therefore about a session being REFUSED. A suite of successful logins
 * passes equally well against the old cookie, which is exactly how this survived.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class SessionRepositoryIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_sessions").withUsername("test").withPassword("test");

    static Pool pool;
    static SessionRepository werkpages;
    static SessionRepository rmm;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations").load().migrate();
        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));
        werkpages = new SessionRepository(pool, Product.WERKPAGES);
        rmm       = new SessionRepository(pool, Product.RMM);
    }

    @AfterAll
    static void tearDownAll() throws Exception { if (pool != null) await(pool.close()); }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE app_sessions, session_revocation_events, users CASCADE").execute());
    }

    // ── the one acceptance, so the refusals mean something ────────────────────

    @Test
    @DisplayName("a session just minted is accepted, once")
    void aFreshSessionIsAccepted() throws Exception {
        UUID user = givenUser("alice");
        String id = await(werkpages.mint(user, "Mozilla/5.0", null));

        Optional<Row> session = await(werkpages.touch(id));

        assertTrue(session.isPresent(), "a session just minted must be usable");
        assertEquals(user, session.get().getUUID("user_id"));
    }

    @Test
    @DisplayName("the id is never stored, so the table cannot be used to impersonate anybody")
    void theIdIsNeverStored() throws Exception {
        UUID user = givenUser("alice");
        String id = await(werkpages.mint(user, null, null));

        /*
          The row is found by the HASH of the id and by nothing else. A dump of this table - from a
          backup, a replica, a log, an errant query - hands over no live sessions, which is a real
          exposure route and does not require the attacker to have had `users` as well.
        */
        Integer byPlaintext = await(pool.preparedQuery(
                "SELECT count(*)::int AS n FROM app_sessions WHERE id_hash = $1::bytea")
            .execute(Tuple.of(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .iterator().next().getInteger("n");
        assertEquals(0, byPlaintext, "the plaintext id must appear nowhere in the table");

        Integer byHash = await(pool.preparedQuery(
                "SELECT count(*)::int AS n FROM app_sessions WHERE id_hash = sha256($1::bytea)")
            .execute(Tuple.of(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .iterator().next().getInteger("n");
        assertEquals(1, byHash, "and it must be findable by its hash, or nothing works");
    }

    // ── the refusals ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a revoked session is refused on the very next request, not at expiry")
    void aRevokedSessionIsRefusedImmediately() throws Exception {
        UUID user = givenUser("alice");
        String id = await(werkpages.mint(user, null, null));
        assertTrue(await(werkpages.touch(id)).isPresent());

        await(werkpages.revoke(id));

        assertTrue(await(werkpages.touch(id)).isEmpty(),
            "THE WHOLE POINT. The old cookie kept working for 24 hours after sign-out because "
            + "clearing it was only ever a request to the browser");
    }

    @Test
    @DisplayName("a session minted by the other product is refused")
    void anotherProductsSessionIsRefused() throws Exception {
        UUID user = givenUser("alice");
        String id = await(rmm.mint(user, null, null));

        assertTrue(await(werkpages.touch(id)).isEmpty(),
            "one shared database and one users table, so without the product scope an RMM session "
            + "would authenticate to WerkPages");
        assertTrue(await(rmm.touch(id)).isPresent(), "and it must still work where it was minted");
    }

    @Test
    @DisplayName("an unknown id is refused, and does not create anything")
    void anUnknownIdIsRefused() throws Exception {
        assertTrue(await(werkpages.touch("not-a-real-session-id")).isEmpty());
        assertTrue(await(werkpages.touch("")).isEmpty());
        assertTrue(await(werkpages.touch(null)).isEmpty());
        assertEquals(0, countSessions());
    }

    @Test
    @DisplayName("an idle-expired session is refused even though its absolute clock has not run out")
    void anIdleExpiredSessionIsRefused() throws Exception {
        UUID user = givenUser("alice");
        String id = await(werkpages.mint(user, null, null));
        // Idle clock in the past, absolute clock far in the future: only the first has run out.
        await(pool.preparedQuery(
                "UPDATE app_sessions SET expires_at = now() - interval '1 minute'")
            .execute());

        assertTrue(await(werkpages.touch(id)).isEmpty(),
            "two clocks, and either one expiring ends the session");
    }

    @Test
    @DisplayName("a session used constantly still ends when its absolute clock runs out")
    void anAbsolutelyExpiredSessionIsRefused() throws Exception {
        UUID user = givenUser("alice");
        String id = await(werkpages.mint(user, null, null));
        /*
          BOTH clocks in the past, and the first attempt at this test taught me why it has to be.

          Setting the absolute clock into the past while leaving the idle one in the future is
          refused by session_absolute_outlives_idle - correctly. Which means "absolutely expired
          but not idle-expired" is IMPOSSIBLE BY CONSTRUCTION: the absolute clock is the ceiling,
          so reaching it implies the idle one has already passed. The constraint is what makes that
          true, and it is worth knowing rather than assuming.
        */
        await(pool.preparedQuery("""
                UPDATE app_sessions SET expires_at = now() - interval '2 minutes',
                                        absolute_expires_at = now() - interval '1 minute'
                """).execute());

        assertTrue(await(werkpages.touch(id)).isEmpty(),
            "this is what bounds the damage of a token taken and used quietly: being used is not "
            + "a reason to live for ever");
    }

    @Test
    @DisplayName("the schema will not let a session outlive its own ceiling")
    void theCeilingCannotBeRaisedAboveItself() throws Exception {
        UUID user = givenUser("alice");
        await(werkpages.mint(user, null, null));

        // What the three fixtures above tried to do by accident, asserted deliberately: the idle
        // clock can never be set beyond the absolute one, so the absolute one is always the
        // binding limit.
        Exception refused = assertThrows(Exception.class, () -> await(pool.preparedQuery("""
                UPDATE app_sessions SET expires_at = now() + interval '365 days'
                """).execute()));
        assertTrue(String.valueOf(refused.getMessage()).contains("session_absolute_outlives_idle"),
            "the refusal must come from that constraint, not from something incidental: "
            + refused.getMessage());
    }

    @Test
    @DisplayName("touching never pushes the idle clock past the absolute one")
    void theIdleClockIsCappedByTheAbsoluteOne() throws Exception {
        UUID user = givenUser("alice");
        String id = await(werkpages.mint(user, null, null));
        // One hour of absolute life left; a touch would otherwise grant fourteen days. Both
        // clocks are moved, because the idle one may never exceed the absolute one.
        await(pool.preparedQuery("""
                UPDATE app_sessions SET expires_at = now() + interval '30 minutes',
                                        absolute_expires_at = now() + interval '1 hour'
                """).execute());

        Row session = await(werkpages.touch(id)).orElseThrow();

        assertFalse(session.getOffsetDateTime("expires_at")
                .isAfter(session.getOffsetDateTime("absolute_expires_at")),
            "LEAST() is what stops a session used every day from outliving its own ceiling");
    }

    // ── ending other people's sessions ───────────────────────────────────────

    @Test
    @DisplayName("sign out everywhere ends every session, in both products")
    void signOutEverywhereCrossesTheProductBoundary() throws Exception {
        UUID user = givenUser("alice");
        String here = await(werkpages.mint(user, null, null));
        String there = await(rmm.mint(user, null, null));
        UUID other = givenUser("bob");
        String bobs = await(werkpages.mint(other, null, null));

        int ended = await(werkpages.revokeAllForUser(user, "signout", null));

        assertEquals(2, ended, "both of this person's sessions, deliberately crossing products: "
            + "signing out everywhere means everywhere");
        assertTrue(await(werkpages.touch(here)).isEmpty());
        assertTrue(await(rmm.touch(there)).isEmpty());
        assertTrue(await(werkpages.touch(bobs)).isPresent(),
            "and only that person's: somebody else's session is not ours to end");
    }

    @Test
    @DisplayName("an administrator ending somebody's sessions is recorded, with the administrator named")
    void anAdministratorRevocationIsAudited() throws Exception {
        UUID user = givenUser("alice");
        UUID admin = givenUser("the-admin");
        await(werkpages.mint(user, null, null));
        await(rmm.mint(user, null, null));

        int ended = await(werkpages.revokeAllForUser(user, "admin_revoked", admin));

        assertEquals(2, ended);
        var events = await(pool.preparedQuery("""
                SELECT user_id, revoked_by, reason FROM session_revocation_events
                 WHERE user_id = $1
                """).execute(Tuple.of(user)));
        assertEquals(2, events.rowCount(), "one record per session ended");
        for (Row e : events) {
            assertEquals(admin, e.getUUID("revoked_by"),
                "a power to end other people's sessions with no accounting is a power that gets "
                + "used once and disputed for ever");
            assertEquals("admin_revoked", e.getString("reason"));
        }
    }

    @Test
    @DisplayName("a person's own sign-out writes no audit record")
    void aSelfServiceSignOutIsNotAnAuditEvent() throws Exception {
        UUID user = givenUser("alice");
        await(werkpages.mint(user, null, null));

        await(werkpages.revokeAllForUser(user, "signout", null));

        assertEquals(0, countEvents(),
            "this table exists for what somebody else did. One row per ordinary sign-out would "
            + "bury the few records anybody needs to find");
    }

    @Test
    @DisplayName("deleting the account ends its sessions and keeps the audit record")
    void deletingTheAccountEndsItsSessions() throws Exception {
        UUID user = givenUser("alice");
        UUID admin = givenUser("the-admin");
        await(werkpages.mint(user, null, null));
        await(werkpages.revokeAllForUser(user, "admin_revoked", admin));

        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(user)));

        assertEquals(0, countSessions(), "no session outlives the account it belonged to");
        assertEquals(1, countEvents(), "the record of the decision outlives both, as it must");
    }

    @Test
    @DisplayName("signing out twice succeeds, and so does signing out with nothing")
    void revokingIsIdempotent() throws Exception {
        UUID user = givenUser("alice");
        String id = await(werkpages.mint(user, null, null));

        await(werkpages.revoke(id));
        await(werkpages.revoke(id));
        await(werkpages.revoke("never-existed"));
        await(werkpages.revoke(null));

        assertTrue(await(werkpages.touch(id)).isEmpty(),
            "signing out is the one action that must never fail in a way that leaves somebody "
            + "unsure whether it worked");
    }

    // ── housekeeping and the list ────────────────────────────────────────────

    @Test
    @DisplayName("the session list shows live sessions and never an id")
    void theSessionListNeverCarriesAnId() throws Exception {
        UUID user = givenUser("alice");
        await(werkpages.mint(user, "Firefox", null));
        String revoked = await(rmm.mint(user, "Chrome", null));
        await(rmm.revoke(revoked));

        var listed = await(werkpages.liveSessionsFor(user));

        assertEquals(1, listed.rowCount(), "a revoked session is not a live one");
        Row only = listed.iterator().next();
        assertEquals("Firefox", only.getString("user_agent"));
        assertThrows(Exception.class, () -> only.getValue("id_hash"),
            "the list must not carry the hash, let alone the id");
    }

    @Test
    @DisplayName("the sweep removes absolutely expired rows and nothing else")
    void theSweepRemovesOnlyDeadRows() throws Exception {
        UUID user = givenUser("alice");
        String live = await(werkpages.mint(user, null, null));
        await(werkpages.mint(user, null, null));
        // The hash is computed in SQL rather than by calling the repository's own helper: that
        // method is package-private and widening it so a test can reach it would be loosening
        // production code to suit a fixture.
        await(pool.preparedQuery("""
                UPDATE app_sessions SET expires_at = now() - interval '2 days',
                                        absolute_expires_at = now() - interval '1 day'
                 WHERE id_hash <> sha256($1::bytea)
                """).execute(Tuple.of(live.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        int swept = await(werkpages.sweepExpired());

        assertEquals(1, swept);
        assertTrue(await(werkpages.touch(live)).isPresent(),
            "housekeeping must not end a session that is still somebody's");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private UUID givenUser(String username) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO users (auth0_id, email, username)
                VALUES ($1, $2, $3) RETURNING id
                """)
            .execute(Tuple.of("auth0|" + username, username + "@example.invalid", username)))
            .iterator().next().getUUID("id");
    }

    private int countSessions() throws Exception {
        return await(pool.query("SELECT count(*)::int AS n FROM app_sessions").execute())
            .iterator().next().getInteger("n");
    }

    private int countEvents() throws Exception {
        return await(pool.query("SELECT count(*)::int AS n FROM session_revocation_events").execute())
            .iterator().next().getInteger("n");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
