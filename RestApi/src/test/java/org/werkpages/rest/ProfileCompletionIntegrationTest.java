package org.werkpages.rest;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
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
 * PROFILE COMPLETION: CHOOSING A USERNAME FOR AN ACCOUNT CREATED WITHOUT ONE.
 *
 * <h2>Why the account has no username in the first place</h2>
 *
 * <p>Universal Login asks for an email and a password. It does not ask for a username, and
 * enabling Auth0's own "Requires Username" would add a second username field that nothing in this
 * product reads - RateMyManagers and WerkPages resolve usernames against their own table. So the
 * callback creates the account with a generated placeholder, {@code user_1a2b3c4d}, and
 * {@code username_is_provisional} set; this endpoint finishes the job.
 *
 * <p>V99 constrains the pair: a flagged username must match {@code ^user_[0-9a-f]{8}$}. That
 * constraint is what makes the flag trustworthy, and several assertions here exist to prove the
 * endpoint cannot leave the two disagreeing.
 *
 * <p>Real Postgres, the real handler, real HTTP, and the real session the authenticator validates.
 */
/* ROUTINE CI: tagged "critical" - security, erasure or data integrity. See the poms. */
@Tag("critical")
@Testcontainers
class ProfileCompletionIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("profile_completion").withUsername("test").withPassword("test");

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
            "client-id", "client-secret", "audience", null, vertx);

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route().failureHandler(ctx -> ctx.response()
            .setStatusCode(ctx.statusCode() > 0 ? ctx.statusCode() : 500).end());
        router.post("/api/auth/complete-profile")
            .handler(new RequestAuthenticator(null, sessions::touch, null))
            .handler(handler::handleCompleteProfile);

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

    // ── the happy path ───────────────────────────────────────────────────────

    @Test
    void choosingAUsernameClaimsItAndClearsTheProvisionalFlag() throws Exception {
        Account a = provisionalAccount("auth0|pc-alice");

        HttpResponse<io.vertx.core.buffer.Buffer> res = complete(a.session, "alice_chose_this");
        assertEquals(200, res.statusCode(), res.bodyAsString());
        assertEquals("alice_chose_this", res.bodyAsJsonObject().getString("username"));

        assertEquals("alice_chose_this", usernameOf(a.userId));
        assertFalse(provisionalFlagOf(a.userId),
            "the flag must clear, or the person is asked to complete their profile for ever");
    }

    // ── the refusals, each for its own reason ────────────────────────────────

    /**
     * A name somebody else holds is refused, and refused by the DATABASE.
     *
     * <p>V99's {@code users_username_ci} index is case-insensitive, so this also proves the
     * endpoint cannot be used to take a name that differs only in case from an existing one -
     * which would let two accounts be mistaken for each other on every public surface.
     */
    @Test
    void aUsernameSomebodyElseHoldsIsRefused() throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO users (auth0_id, email, username) VALUES ($1, $2, $3)")
            .execute(Tuple.of("auth0|pc-incumbent", "incumbent@example.invalid", "TakenName")));

        Account a = provisionalAccount("auth0|pc-bob");

        HttpResponse<io.vertx.core.buffer.Buffer> res = complete(a.session, "takenname");
        assertEquals(409, res.statusCode(), res.bodyAsString());
        assertEquals("username_taken", res.bodyAsJsonObject().getString("error"));

        assertTrue(provisionalFlagOf(a.userId),
            "a refused claim must leave the profile incomplete, not half-applied");
    }

    /**
     * A second completion is refused, which is what stops this becoming a username-change endpoint.
     *
     * <p>Changing an established username is a different feature with different consequences: old
     * links, old mentions, and the possibility of taking a name somebody has just released. The
     * {@code WHERE username_is_provisional} predicate is the whole guard, and this is its test.
     */
    @Test
    void anAlreadyCompleteProfileCannotBeChangedThroughThisEndpoint() throws Exception {
        Account a = provisionalAccount("auth0|pc-carol");
        assertEquals(200, complete(a.session, "carol_first_choice").statusCode());

        HttpResponse<io.vertx.core.buffer.Buffer> second = complete(a.session, "carol_second_choice");
        assertEquals(409, second.statusCode(), second.bodyAsString());
        assertEquals("profile_already_complete", second.bodyAsJsonObject().getString("error"));
        assertEquals("carol_first_choice", usernameOf(a.userId), "the name must not have moved");
    }

    /**
     * A placeholder-SHAPED username cannot be claimed.
     *
     * <p>The publication guard in {@code ManagerService} matches on shape, so somebody who
     * claimed {@code user_deadbeef} would have a pseudonym published on every review they ever
     * wrote, for ever, and would have no way to tell why. Refusing the shape is kinder than
     * honouring a choice that quietly does not work.
     */
    @Test
    void aUsernameShapedLikeAPlaceholderIsRefused() throws Exception {
        Account a = provisionalAccount("auth0|pc-dave");
        HttpResponse<io.vertx.core.buffer.Buffer> res = complete(a.session, "user_deadbeef");
        assertEquals(400, res.statusCode(), res.bodyAsString());
        assertTrue(provisionalFlagOf(a.userId));
    }

    @Test
    void invalidUsernamesAreRefusedWithoutTouchingTheAccount() throws Exception {
        Account a = provisionalAccount("auth0|pc-erin");
        for (String bad : new String[]{"ab", "has spaces", "has/slash", "", "x".repeat(31)}) {
            HttpResponse<io.vertx.core.buffer.Buffer> res = complete(a.session, bad);
            assertEquals(400, res.statusCode(), "should refuse '" + bad + "': " + res.bodyAsString());
        }
        assertTrue(provisionalFlagOf(a.userId));
        assertTrue(usernameOf(a.userId).startsWith("user_"),
            "the placeholder must survive every refusal");
    }

    @Test
    void anUnauthenticatedCallerIsRefused() throws Exception {
        HttpResponse<io.vertx.core.buffer.Buffer> res = client.post("/api/auth/complete-profile")
            .sendJsonObject(new JsonObject().put("username", "nobody_in_particular"))
            .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        assertEquals(401, res.statusCode());
    }

    /**
     * The account the SESSION names is the one that changes, not one named in the body.
     *
     * <p>There is no user id in the request by design. Asserted anyway, because "the endpoint
     * takes the id from the request" is the single most common way an authorization boundary gets
     * crossed, and an assertion here would fail the moment somebody added that parameter for
     * convenience.
     */
    @Test
    void theSessionDecidesWhoseProfileIsCompleted() throws Exception {
        Account mine   = provisionalAccount("auth0|pc-frank");
        Account theirs = provisionalAccount("auth0|pc-grace");

        HttpResponse<io.vertx.core.buffer.Buffer> res = client.post("/api/auth/complete-profile")
            .putHeader("Cookie", "__Host-wp_session=" + mine.session)
            .sendJsonObject(new JsonObject()
                .put("username", "frank_chose_this")
                .put("userId", theirs.userId.toString())      // ignored, and must be
                .put("auth0Id", "auth0|pc-grace"))            // likewise
            .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);

        assertEquals(200, res.statusCode());
        assertEquals("frank_chose_this", usernameOf(mine.userId));
        assertTrue(usernameOf(theirs.userId).startsWith("user_"),
            "the other account must be untouched");
        assertTrue(provisionalFlagOf(theirs.userId));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Account(UUID userId, String session) {}

    /** An account exactly as the callback creates one: generated name, flag set, with a session. */
    private Account provisionalAccount(String auth0Id) throws Exception {
        String placeholder = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        UUID userId = await(pool.preparedQuery("""
                INSERT INTO users (auth0_id, email, username, username_is_provisional)
                VALUES ($1, $2, $3, TRUE) RETURNING id
                """)
            .execute(Tuple.of(auth0Id, auth0Id + "@example.invalid", placeholder)))
            .iterator().next().getUUID("id");
        return new Account(userId, await(sessions.mint(userId, "test", null)));
    }

    private HttpResponse<io.vertx.core.buffer.Buffer> complete(String session, String username) {
        try {
            return client.post("/api/auth/complete-profile")
                .putHeader("Cookie", "__Host-wp_session=" + session)
                .sendJsonObject(new JsonObject().put("username", username))
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (Exception e) { throw new AssertionError("request failed", e); }
    }

    private String usernameOf(UUID userId) throws Exception {
        return await(pool.preparedQuery("SELECT username FROM users WHERE id = $1")
            .execute(Tuple.of(userId))).iterator().next().getString("username");
    }

    private boolean provisionalFlagOf(UUID userId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT username_is_provisional FROM users WHERE id = $1")
            .execute(Tuple.of(userId)))
            .iterator().next().getBoolean("username_is_provisional");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
