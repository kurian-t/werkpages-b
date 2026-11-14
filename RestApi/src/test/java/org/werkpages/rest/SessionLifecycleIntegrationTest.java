package org.werkpages.rest;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE WHOLE SESSION LIFECYCLE: A REAL DATABASE, OVER REAL HTTP.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Two suites already covered the halves. {@code SessionRepositoryIntegrationTest} proves the
 * table against a real Postgres - the two clocks, the revocation, the product scoping - by calling
 * the repository directly. {@code RequestAuthenticatorHttpTest} proves the handler over real HTTP,
 * with the session lookup programmed in process.
 *
 * <p>Neither could show that <b>the row the database wrote is the row the authenticator accepts</b>.
 * That was true by construction and by inspection, which is how the {@code findBySlug} arity defect
 * survived: a statement with two placeholders, a call site passing one, every layer tested
 * separately and nothing joining them.
 *
 * <p>So everything here is real: a migrated Postgres in a container, the real
 * {@link SessionRepository} minting into it, the real {@link RequestAuthenticator} mounted on a
 * real HTTP server, and a real client presenting real {@code Cookie} headers. Nothing is stubbed
 * except Auth0, which is absent rather than stubbed - no token path is exercised here.
 *
 * <h2>Why this module</h2>
 *
 * <p>The security handler is here and the database access is in Api, and Api cannot depend on
 * RestApi. This is the only module that can hold both, which is why {@code RestApi/pom.xml} gained
 * Testcontainers and Failsafe for it.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class SessionLifecycleIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("session_lifecycle").withUsername("test").withPassword("test");

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

        Router router = Router.router(vertx);
        router.route().failureHandler(ctx -> ctx.response()
            .setStatusCode(ctx.statusCode() > 0 ? ctx.statusCode() : 500).end());
        // No JWTAuth: this suite is about sessions, and passing null proves the session path needs
        // no token machinery behind it.
        router.route("/secured")
            .handler(new RequestAuthenticator("cron-secret", sessions::touch, null))
            .handler(ctx -> ctx.response().putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("auth0Id", (String) ctx.get("auth0Id")).encode()));

        CompletableFuture<Integer> up = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0)
            .onSuccess(s -> { server = s; up.complete(s.actualPort()); })
            .onFailure(up::completeExceptionally);
        int port = up.get(30, TimeUnit.SECONDS);
        client = WebClient.create(vertx,
            new WebClientOptions().setDefaultHost("127.0.0.1").setDefaultPort(port));
    }

    /**
     * WerkPages owns the migrations, so its own classpath is authoritative. RateMyManagers' copy of
     * this test reads {@code PLATFORM_MIGRATIONS_DIR}, which its Failsafe configuration sets,
     * because that repository's own folder is frozen at V43.
     */
    private static String migrationLocation() {
        String shared = System.getenv("PLATFORM_MIGRATIONS_DIR");
        if (shared != null && !shared.isBlank()) {
            if (!Files.isDirectory(Paths.get(shared))) {
                throw new IllegalStateException(
                    "PLATFORM_MIGRATIONS_DIR is set to " + shared + " but that is not a directory.");
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

    // ── the journey ──────────────────────────────────────────────────────────

    /**
     * Mint, use, revoke, refused. The complete thing, in one test, because the point is the
     * TRANSITION: a session that works and then does not, without anything else changing.
     */
    @Test
    void aMintedSessionAuthenticatesUntilItIsRevoked() throws Exception {
        UUID userId = insertUser("auth0|lifecycle-alice", "alice-lifecycle");
        String opaque = await(sessions.mint(userId, "Mozilla/5.0 test", null));

        HttpResponse<io.vertx.core.buffer.Buffer> accepted = get(opaque);
        assertEquals(200, accepted.statusCode(),
            "a session the database just minted must authenticate a real request");
        assertEquals("auth0|lifecycle-alice", accepted.bodyAsJsonObject().getString("auth0Id"),
            "the subject must come from the user row the session points at");

        await(sessions.revoke(opaque));

        HttpResponse<io.vertx.core.buffer.Buffer> refused = get(opaque);
        assertEquals(401, refused.statusCode(), "revocation must take effect on the next request");
        assertTrue(String.valueOf(refused.getHeader("Set-Cookie")).contains("Max-Age=0"),
            "a revoked session must be cleared from the browser");
    }

    /**
     * An expired session is refused, with the expiry written by the database rather than waited for.
     *
     * <p>Fourteen days is not a thing a test can wait out, so the row is aged directly. That is
     * honest here precisely because the clock arithmetic itself is proven in
     * {@code SessionRepositoryIntegrationTest}; what this adds is that the HTTP path honours it.
     */
    @Test
    void anExpiredSessionIsRefusedOverHttp() throws Exception {
        UUID userId = insertUser("auth0|lifecycle-bob", "bob-lifecycle");
        String opaque = await(sessions.mint(userId, null, null));
        assertEquals(200, get(opaque).statusCode(), "live before being aged");

        await(pool.preparedQuery(
                "UPDATE app_sessions SET expires_at = now() - interval '1 second' WHERE user_id = $1")
            .execute(Tuple.of(userId)));

        assertEquals(401, get(opaque).statusCode());
    }

    /**
     * A session minted for the other product does not authenticate here.
     *
     * <p>The two products share one database and one {@code app_sessions} table. Without the
     * product predicate in {@code touch}, a WerkPages session would authenticate a RateMyManagers
     * request, which is a cross-product authentication bypass through a shared table rather than
     * through anything either application does wrong on its own.
     */
    @Test
    void aSessionFromTheOtherProductIsRefused() throws Exception {
        UUID userId = insertUser("auth0|lifecycle-carol", "carol-lifecycle");
        SessionRepository otherProduct = new SessionRepository(pool, Product.RMM);
        String opaque = await(otherProduct.mint(userId, null, null));

        assertEquals(401, get(opaque).statusCode(),
            "this server is WerkPages; an RMM session must mean nothing to it");
    }

    /**
     * Revoking everything for a user ends the session the request is holding.
     *
     * <p>This is "sign out everywhere" and an administrator's suspension, both of which go through
     * {@code revokeAllForUser}. Two sessions are minted so the test cannot pass by ending only the
     * one it happens to present.
     */
    @Test
    void revokingEverythingForAUserEndsEverySession() throws Exception {
        UUID userId = insertUser("auth0|lifecycle-dave", "dave-lifecycle");
        String first  = await(sessions.mint(userId, "phone", null));
        String second = await(sessions.mint(userId, "laptop", null));
        assertEquals(200, get(first).statusCode());
        assertEquals(200, get(second).statusCode());

        UUID admin = insertUser("auth0|lifecycle-admin", "admin-lifecycle");
        int revoked = await(sessions.revokeAllForUser(userId, "admin_revoked", admin));
        assertEquals(2, revoked, "both sessions, not just the most recent");

        assertEquals(401, get(first).statusCode());
        assertEquals(401, get(second).statusCode());
    }

    /**
     * Deleting the account makes its sessions unusable, through the foreign key alone.
     *
     * <p>Account deletion does not mention sessions anywhere, and must not have to. The cascade
     * removes the rows, and {@code touch}'s join to {@code users} means that even a row that
     * somehow survived could not authenticate a user who no longer exists.
     */
    @Test
    void deletingTheAccountEndsItsSessions() throws Exception {
        UUID userId = insertUser("auth0|lifecycle-erin", "erin-lifecycle");
        String opaque = await(sessions.mint(userId, null, null));
        assertEquals(200, get(opaque).statusCode());

        await(pool.preparedQuery("DELETE FROM users WHERE id = $1").execute(Tuple.of(userId)));

        assertEquals(401, get(opaque).statusCode());
    }

    /**
     * The cron credential works against a real database too, holding no session at all.
     *
     * <p>Proven in the HTTP suite against a stub; repeated here because the scheduled jobs run
     * against the real thing, and "the authenticator returns before touching the database" is a
     * claim worth holding against a database that would notice.
     */
    @Test
    void theCronCredentialNeedsNoSession() throws Exception {
        HttpResponse<io.vertx.core.buffer.Buffer> res = client.get("/secured")
            .putHeader("X-Cron-Secret", "cron-secret")
            .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        assertEquals(200, res.statusCode());
        assertEquals("cron", res.bodyAsJsonObject().getString("auth0Id"));
    }

    /**
     * Using a session extends its idle clock, and never past the absolute one.
     *
     * <p>Asserted here rather than only in the repository suite because the extension happens as a
     * side effect of authenticating a REQUEST. A refactor that validated with a SELECT - which
     * looks harmless, and cheaper - would leave every session expiring fourteen days after login
     * however much it was used.
     */
    @Test
    void usingASessionOverHttpExtendsIt() throws Exception {
        UUID userId = insertUser("auth0|lifecycle-frank", "frank-lifecycle");
        String opaque = await(sessions.mint(userId, null, null));

        await(pool.preparedQuery(
                "UPDATE app_sessions SET expires_at = now() + interval '1 day' WHERE user_id = $1")
            .execute(Tuple.of(userId)));

        assertEquals(200, get(opaque).statusCode());

        java.time.OffsetDateTime after = await(pool.preparedQuery(
                "SELECT expires_at FROM app_sessions WHERE user_id = $1")
            .execute(Tuple.of(userId))).iterator().next().getOffsetDateTime("expires_at");

        assertTrue(after.isAfter(java.time.OffsetDateTime.now().plusDays(10)),
            "a used session must have its idle clock pushed back out, not left at one day: " + after);
        assertTrue(after.isBefore(java.time.OffsetDateTime.now().plusDays(91)),
            "and never past the absolute lifetime: " + after);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpResponse<io.vertx.core.buffer.Buffer> get(String opaqueId) {
        try {
            return client.get("/secured")
                .putHeader("Cookie", "__Host-wp_session=" + opaqueId)
                .send().toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("request failed", e);
        }
    }

    private UUID insertUser(String auth0Id, String username) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, email, username) VALUES ($1, $2, $3) RETURNING id")
            .execute(Tuple.of(auth0Id, username + "@example.invalid", username)))
            .iterator().next().getUUID("id");
    }

    private static <T> T await(io.vertx.core.Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
