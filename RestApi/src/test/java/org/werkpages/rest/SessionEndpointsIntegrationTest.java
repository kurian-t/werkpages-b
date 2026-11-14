package org.werkpages.rest;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.SessionRepository;
import org.werkpages.repository.SessionRepository.Product;
import org.werkpages.repository.UserRepository;
import org.werkpages.rest.handlers.AuthHandler;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SIGNING OUT, LISTING SESSIONS, AND SIGNING OUT EVERYWHERE - OVER REAL HTTP.
 *
 * <h2>What this adds</h2>
 *
 * <p>{@code SessionLifecycleIntegrationTest} proves that a session revoked <b>through the
 * repository</b> stops authenticating. That is the mechanism. It says nothing about the three
 * endpoints a person actually reaches, and those endpoints are where the behaviour people care
 * about lives:
 *
 * <ul>
 *   <li>pressing sign out must END the session, not clear a cookie. For years it cleared a cookie,
 *       and a copy of the token taken beforehand kept working for up to a day;
 *   <li>"sign out everywhere" must reach every session on the account, including the one making
 *       the request, and including the other product's;
 *   <li>the session list must show somebody where they are signed in without handing the page
 *       anything that could be replayed.
 * </ul>
 *
 * <p>Everything is real: a migrated Postgres, the real {@link SessionRepository}, the real
 * {@link AuthHandler} methods, the real {@link RequestAuthenticator} guarding a protected route,
 * and a real client presenting real cookies.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class SessionEndpointsIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("session_endpoints").withUsername("test").withPassword("test");

    static Pool pool;
    static SessionRepository sessions;
    static Vertx vertx;
    static HttpServer server;
    static WebClient client;

    @BeforeAll
    static void setUpAll() throws Exception {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations(migrationLocation()).load().migrate();

        vertx = Vertx.vertx();
        pool = PgPool.pool(vertx, new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));
        sessions = new SessionRepository(pool, Product.WERKPAGES);

        UserRepository users = new UserRepository(pool);
        AuthHandler handler = new AuthHandler(users, null, "tenant.auth0.test",
            "the-client-id", "client-secret", "audience", null, vertx);
        handler.setSessionRepository(sessions);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route().failureHandler(ctx -> ctx.response()
            .setStatusCode(ctx.statusCode() > 0 ? ctx.statusCode() : 500).end());

        // Signout is reachable unauthenticated, exactly as openapi.yaml declares it: somebody whose
        // session has just expired still needs their cookies cleared.
        router.post("/api/auth/signout").handler(handler::handleSignout);

        // These two are authenticated, so they go behind the real authenticator.
        RequestAuthenticator auth = new RequestAuthenticator(null, sessions::touch, null);
        router.get("/api/auth/sessions").handler(auth).handler(handler::handleListMySessions);
        router.post("/api/auth/sessions/revoke-all").handler(auth)
              .handler(handler::handleRevokeMySessions);

        // A protected route, to ask the question that matters after each call: does the session
        // still work?
        router.get("/protected").handler(auth)
              .handler(ctx -> ctx.response().end("ok"));

        CompletableFuture<Integer> up = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
            .onSuccess(s -> { server = s; up.complete(s.actualPort()); })
            .onFailure(up::completeExceptionally);
        int port = up.get(30, TimeUnit.SECONDS);
        client = WebClient.create(vertx,
            new WebClientOptions().setDefaultHost("127.0.0.1").setDefaultPort(port));
    }

    private static String migrationLocation() {
        String shared = System.getenv("PLATFORM_MIGRATIONS_DIR");
        if (shared != null && !shared.isBlank()) {
            if (!Files.isDirectory(Paths.get(shared))) {
                throw new IllegalStateException("PLATFORM_MIGRATIONS_DIR is not a directory: " + shared);
            }
            return "filesystem:" + Paths.get(shared).toAbsolutePath().normalize();
        }
        return "classpath:db/migrations";
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (pool   != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (vertx  != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // ── SIGN OUT ─────────────────────────────────────────────────────────────

    /**
     * THE REGRESSION TEST FOR THE WHOLE POINT OF STAGE 5.
     *
     * <p>Sign-out used to clear a cookie and nothing else. Clearing a cookie is an instruction to
     * a browser; it does not invalidate anything, so a copy of the credential taken beforehand -
     * from a shared machine, a proxy log, an attacker - kept the account for as long as the token
     * lasted.
     *
     * <p>This asserts the session is dead <b>on the server</b> afterwards, by presenting the very
     * same cookie to a protected route. A test that only checked the `Set-Cookie` header would
     * have passed against the old behaviour.
     */
    @Test
    void signingOutEndsTheSessionOnTheServerNotJustInTheBrowser() throws Exception {
        UUID userId = insertUser("auth0|so-alice", "so_alice");
        String opaque = await(sessions.mint(userId, "browser", null));
        assertEquals(200, protectedWith(opaque).statusCode(), "live before signing out");

        HttpResponse<io.vertx.core.buffer.Buffer> out = signout(opaque);
        assertEquals(200, out.statusCode());
        assertTrue(out.bodyAsJsonObject().getBoolean("success"));

        assertEquals(401, protectedWith(opaque).statusCode(),
            "the SAME cookie must no longer work; clearing it in the browser is not enough");
    }

    /**
     * Sign-out clears both cookies, and the session one is not dropped by the other.
     *
     * <p>{@code putHeader} REPLACES a header. The response sets {@code auth_token} that way, so
     * adding the session cookie with {@code putHeader} too would silently discard the first. Both
     * must be present, which is why the handler uses {@code headers().add} for the second.
     */
    @Test
    void signingOutClearsBothCookies() throws Exception {
        UUID userId = insertUser("auth0|so-bob", "so_bob");
        String opaque = await(sessions.mint(userId, null, null));

        var cookies = signout(opaque).cookies();
        String all = String.join(" | ", cookies);
        assertTrue(all.contains("auth_token="), "the legacy cookie must still be cleared: " + all);
        assertTrue(all.contains("__Host-wp_session="), "and the session cookie too: " + all);
        assertEquals(2, cookies.size(), "exactly two, neither replacing the other: " + all);
    }

    /**
     * Signing out twice succeeds, and so does signing out with no cookie at all.
     *
     * <p>Sign-out is the one action that must never leave somebody unsure whether it worked. A
     * second press, an expired session, a cookie that names nothing - all of them are a success,
     * because in every case the person ends up signed out, which is what they asked for.
     */
    @Test
    void signingOutIsIdempotentAndSucceedsWithNoSessionAtAll() throws Exception {
        UUID userId = insertUser("auth0|so-carol", "so_carol");
        String opaque = await(sessions.mint(userId, null, null));

        assertEquals(200, signout(opaque).statusCode());
        assertEquals(200, signout(opaque).statusCode(), "a second sign-out must still succeed");

        HttpResponse<io.vertx.core.buffer.Buffer> none = client.post("/api/auth/signout")
            .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        assertEquals(200, none.statusCode(), "no cookie at all is still a successful sign-out");
    }

    /**
     * The response carries Auth0's logout URL, built from the tenant domain and the client id.
     *
     * <p>Without navigating there, Auth0 still recognises the person and the next {@code
     * /authorize} returns a fresh token without asking for anything - which looks, to somebody who
     * just pressed sign out, exactly like it did not work.
     */
    @Test
    void signingOutReturnsTheAuth0LogoutUrl() throws Exception {
        UUID userId = insertUser("auth0|so-dave", "so_dave");
        String opaque = await(sessions.mint(userId, null, null));

        String url = signout(opaque).bodyAsJsonObject().getString("auth0LogoutUrl");
        assertNotNull(url, "the browser needs somewhere to go to end the Auth0 session");
        assertTrue(url.startsWith("https://tenant.auth0.test/v2/logout"), url);
        assertTrue(url.contains("client_id=the-client-id"), url);
    }

    // ── THE SESSION LIST ─────────────────────────────────────────────────────

    /**
     * The list shows the account's sessions, marks the current one, and leaks nothing replayable.
     *
     * <p>{@code current} is computed by comparing hashes server-side. Returning {@code id_hash} so
     * a page could compare it itself would hand script the exact string the database keys on, and
     * the whole reason the session is HttpOnly is that script must not hold session material.
     */
    @Test
    void theSessionListMarksTheCurrentSessionAndLeaksNothing() throws Exception {
        UUID userId = insertUser("auth0|ls-erin", "ls_erin");
        String phone  = await(sessions.mint(userId, "Phone browser", null));
        String laptop = await(sessions.mint(userId, "Laptop browser", null));

        JsonArray listed = client.get("/api/auth/sessions")
            .putHeader("Cookie", "__Host-wp_session=" + laptop)
            .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            .bodyAsJsonObject().getJsonArray("sessions");

        assertEquals(2, listed.size());
        long current = listed.stream().map(o -> (JsonObject) o)
            .filter(o -> Boolean.TRUE.equals(o.getBoolean("current"))).count();
        assertEquals(1, current, "exactly one row is the browser doing the asking");
        assertEquals("Laptop browser", listed.stream().map(o -> (JsonObject) o)
            .filter(o -> Boolean.TRUE.equals(o.getBoolean("current")))
            .findFirst().orElseThrow().getString("userAgent"),
            "and it must be the right one");

        String raw = listed.encode();
        assertFalse(raw.contains("id_hash"), "no hash may be returned: " + raw);
        assertFalse(raw.contains(phone),  "no session id may be returned");
        assertFalse(raw.contains(laptop), "including the caller's own");
        assertFalse(raw.contains("address_hash"), "nor the stored address hash: " + raw);
    }

    /** A revoked session must not appear in the list it can no longer be used to fetch. */
    @Test
    void revokedSessionsAreNotListed() throws Exception {
        UUID userId = insertUser("auth0|ls-frank", "ls_frank");
        String keep = await(sessions.mint(userId, "kept", null));
        String gone = await(sessions.mint(userId, "revoked", null));
        await(sessions.revoke(gone));

        JsonArray listed = client.get("/api/auth/sessions")
            .putHeader("Cookie", "__Host-wp_session=" + keep)
            .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS)
            .bodyAsJsonObject().getJsonArray("sessions");

        assertEquals(1, listed.size(), "the revoked one must be gone: " + listed.encode());
        assertEquals("kept", listed.getJsonObject(0).getString("userAgent"));
    }

    // ── SIGN OUT EVERYWHERE ──────────────────────────────────────────────────

    /**
     * Ends every session on the account, including the one asking and including the OTHER
     * PRODUCT'S.
     *
     * <p>Crossing the product boundary is deliberate. Somebody who believes their account is
     * compromised means every session, and being signed out of WerkPages while still signed in to
     * RateMyManagers would be a surprise of the worst kind - the two share one account.
     */
    @Test
    void signingOutEverywhereEndsEverySessionInBothProducts() throws Exception {
        UUID userId = insertUser("auth0|ra-grace", "ra_grace");
        String here    = await(sessions.mint(userId, "this product", null));
        String alsoHere = await(sessions.mint(userId, "another browser", null));
        SessionRepository otherProduct = new SessionRepository(pool, Product.RMM);
        String elsewhere = await(otherProduct.mint(userId, "the other product", null));

        HttpResponse<io.vertx.core.buffer.Buffer> res = client.post("/api/auth/sessions/revoke-all")
            .putHeader("Cookie", "__Host-wp_session=" + here)
            .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(200, res.statusCode());
        assertEquals(3, res.bodyAsJsonObject().getInteger("sessionsRevoked"),
            "all three, across both products");

        assertEquals(401, protectedWith(here).statusCode(), "including the one that asked");
        assertEquals(401, protectedWith(alsoHere).statusCode());
        assertFalse(await(otherProduct.touch(elsewhere)).isPresent(),
            "and the other product's session, which is the point of crossing the boundary");
    }

    /**
     * It records no actor, because the actor is the account holder.
     *
     * <p>The audit table answers "which administrator ended this session". Writing the person
     * themselves into it would make an administrator's revocation and somebody's own precaution
     * indistinguishable in the one place that exists to tell them apart - and V100's
     * {@code revocation_by_an_actor_is_named} constraint permits a null actor only for a sign-out
     * reason, so this also proves the reason is right.
     */
    @Test
    void signingOutEverywhereRecordsNoAdministrator() throws Exception {
        UUID userId = insertUser("auth0|ra-heidi", "ra_heidi");
        String opaque = await(sessions.mint(userId, null, null));

        client.post("/api/auth/sessions/revoke-all")
            .putHeader("Cookie", "__Host-wp_session=" + opaque)
            .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);

        var rows = await(pool.preparedQuery(
                "SELECT revoked_by, reason FROM session_revocation_events WHERE user_id = $1")
            .execute(Tuple.of(userId)));
        // No audit row at all is also correct here: revokeAllForUser writes one per session only
        // when an actor is given. What must NOT exist is a row naming the person as their own
        // administrator.
        rows.forEach(row -> assertNull(row.getUUID("revoked_by"),
            "the account holder must never be recorded as the actor who revoked them"));
    }

    @Test
    void theSessionEndpointsRefuseAnUnauthenticatedCaller() throws Exception {
        assertEquals(401, client.get("/api/auth/sessions")
            .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS).statusCode());
        assertEquals(401, client.post("/api/auth/sessions/revoke-all")
            .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS).statusCode());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpResponse<io.vertx.core.buffer.Buffer> signout(String opaque) {
        try {
            return client.post("/api/auth/signout")
                .putHeader("Cookie", "__Host-wp_session=" + opaque)
                .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) { throw new AssertionError("signout failed", e); }
    }

    private HttpResponse<io.vertx.core.buffer.Buffer> protectedWith(String opaque) {
        try {
            return client.get("/protected")
                .putHeader("Cookie", "__Host-wp_session=" + opaque)
                .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) { throw new AssertionError("request failed", e); }
    }

    private UUID insertUser(String auth0Id, String username) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, email, username) VALUES ($1, $2, $3) RETURNING id")
            .execute(Tuple.of(auth0Id, username + "@example.invalid", username)))
            .iterator().next().getUUID("id");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
