package org.werkpages.rest;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.api.contract.RouterFactoryOptions;
import io.vertx.ext.web.api.contract.openapi3.OpenAPI3RouterFactory;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.SessionRepository;
import org.werkpages.repository.SessionRepository.Product;
import org.werkpages.repository.UserRepository;
import org.werkpages.rest.handlers.ModerationHandler;
import org.werkpages.service.AdminService;
import org.werkpages.service.ModerationService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONE MODERATION JOURNEY, END TO END, OVER REAL HTTP, WITH A LEGITIMATE ADMIN SESSION.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Moderation was verified at three layers and never as one chain. {@code ModerationIntegration
 * Test} calls the service directly against a real database. {@code RequestAuthenticatorHttpTest}
 * drives the security handler over real HTTP against a stubbed session store. The Playwright specs
 * drive a real browser against a mocked API. Each is sound, and between them sat the thing that
 * actually breaks: the chain. A route declared in {@code openapi.yaml} but never wired to a
 * handler, a security handler the operation does not require, a path parameter the spec types
 * differently from the handler, a 403 arriving as a 500 - every one of those passes all three
 * suites and fails in a browser.
 *
 * <h2>What is real here</h2>
 *
 * <p>The HTTP server and client. The router <b>built from the shipped {@code openapi.yaml}</b> by
 * the same {@link OpenAPI3RouterFactory} {@code MainVerticle} boots with, including its request
 * validation and its own failure handling. The real {@link RequestAuthenticator} mounted as the
 * {@code bearerAuth} security handler, exactly as {@code MainVerticle} mounts it. The real
 * {@link SessionRepository} against a real PostgreSQL. The real {@link ModerationHandler},
 * {@link ModerationService} and {@link AdminService}. The real schema, migrated by Flyway.
 *
 * <p><b>The admin session is legitimate, not a mock.</b> It is minted by
 * {@link SessionRepository#mint}, which is the same call the OAuth callback makes when somebody
 * signs in through Universal Login, and presented as the {@code __Host-wp_session} cookie a
 * browser would send. The only thing absent is Auth0 itself, which issues the ID token the
 * callback exchanges BEFORE minting this session; everything downstream of that exchange is the
 * production path. A test that drove Auth0 would need live credentials and would skip itself
 * wherever they were absent, which is how a test comes to prove nothing.
 *
 * <h2>Why the tests are ordered</h2>
 *
 * <p><b>Named {@code ...IntegrationTest}, and the name is load-bearing.</b> It was
 * {@code ...HttpTest}, which matches surefire's {@code *Test} pattern, so it ran in the UNIT phase
 * - where {@code DOCKER_HOST} is deliberately unset, because that variable lives in the Failsafe
 * configuration. The symptom was "Could not find a valid Docker environment" on a test that passes
 * perfectly well when Failsafe runs it. {@code RestApi/pom.xml} already carries a comment saying
 * exactly this about three other suites.
 *
 * <p>They are one journey, in the order a moderator performs it, against one fixture: refused
 * without a credential, refused with an ordinary account's, admitted with the admin's, the queue
 * read, a release refused while something still blocks it, the employment accepted, the rating
 * released, the queue empty. Splitting it into eight independent fixtures would test eight
 * screens and not the journey, and the journey is what was missing.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminModerationJourneyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("admin_journey").withUsername("test").withPassword("test");

    private static final String CRON_SECRET = "cron-secret-not-under-test-here";

    static Vertx      vertx;
    static Pool       pool;
    static HttpServer server;
    static WebClient  client;

    /** The admin's session cookie value, and an ordinary account's, both genuinely minted. */
    static String adminSession;
    static String ordinarySession;

    static long managerId;
    static long companyId;
    static long roleId;
    static UUID reviewId;

    @BeforeAll
    void boot() throws Exception {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations(migrationLocation()).load().migrate();

        vertx = Vertx.vertx();
        pool = PgPool.pool(vertx, new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        UserRepository    users    = new UserRepository(pool);
        ManagerRepository managers = new ManagerRepository(pool);
        ReviewRepository  reviews  = new ReviewRepository(pool);
        AdminService admin = new AdminService(users, managers, reviews,
            new EditRepository(pool), new NotificationRepository(pool));
        ModerationHandler handler = new ModerationHandler(new ModerationService(pool, admin));

        SessionRepository sessions = new SessionRepository(pool, Product.WERKPAGES);

        /*
          The router from the SHIPPED spec, with security handlers REQUIRED.

          setRequireSecurityHandlers(false) is what the validation-only tests in this package use,
          and it is exactly what must not happen here: it would let an operation whose spec forgot
          its `security:` block pass this suite while being wide open in production. Left at its
          default, the factory refuses to build unless every secured operation has its handler,
          which is itself an assertion.
        */
        CompletableFuture<Router> built = new CompletableFuture<>();
        OpenAPI3RouterFactory.create(vertx, "openapi.yaml", ar -> {
            if (ar.failed()) { built.completeExceptionally(ar.cause()); return; }
            OpenAPI3RouterFactory rf = ar.result();
            rf.setOptions(new RouterFactoryOptions().setMountNotImplementedHandler(true));
            rf.addSecurityHandler("bearerAuth",
                new RequestAuthenticator(CRON_SECRET, sessions::touch, null));
            rf.addHandlerByOperationId("adminModerationSummary",            handler::handleSummary);
            rf.addHandlerByOperationId("adminModerationHeldRatings",        handler::handleHeldRatings);
            rf.addHandlerByOperationId("adminModerationPublishHeldRating",  handler::handlePublishHeldRating);
            rf.addHandlerByOperationId("adminModerationAcceptEmployment",   handler::handleAcceptEmployment);
            rf.addHandlerByOperationId("adminModerationPendingEmployments", handler::handlePendingEmployments);
            built.complete(rf.getRouter());
        });
        Router router = built.get(60, TimeUnit.SECONDS);

        server = vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        client = WebClient.create(vertx, new WebClientOptions()
            .setDefaultHost("127.0.0.1").setDefaultPort(server.actualPort()));

        // Two real accounts and two real sessions.
        UUID adminId    = insertUser("auth0|journey-admin",    "journeyadmin",    "admin");
        UUID ordinaryId = insertUser("auth0|journey-ordinary", "journeyordinary", "user");
        adminSession    = await(sessions.mint(adminId,    "JourneyTest/1.0", new byte[32]));
        ordinarySession = await(sessions.mint(ordinaryId, "JourneyTest/1.0", new byte[32]));

        // One manager with a proposed employment and a rating held against it. The withdrawn route
        // is used because it needs no claim, and the queue has to answer for it either way.
        companyId = await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES "
              + "('Halverson Logistics','ghost','halverson-logistics') RETURNING id")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        managerId = await(pool.preparedQuery(
                "INSERT INTO managers(name,company,company_id,title,image,status,approval_status,"
              + "overall_rating,reviews_count,category_averages,slug) "
              + "VALUES ('Priya Raghunathan','Halverson Logistics',$1,'Dispatch Lead','img',"
              + "'active','approved',NULL,0,'{}','priya-raghunathan') RETURNING id")
            .execute(Tuple.of(companyId)).map(rs -> rs.iterator().next().getLong("id")));
        roleId = await(pool.preparedQuery(
                "INSERT INTO career_history (manager_id, company, title, start_date, company_id, "
              + "period_known, state, source) "
              + "VALUES ($1,'Halverson Logistics','Dispatch Lead',"
              + "($2::date)::timestamp AT TIME ZONE 'UTC',$3,TRUE,'proposed','contributor') "
              + "RETURNING id")
            .execute(Tuple.of(managerId, LocalDate.parse("2024-01-15"), companyId))
            .map(rs -> rs.iterator().next().getLong("id")));
        reviewId = await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_title,
                                     manager_company, communication_style,
                                     perceived_approachability, perceived_clarity_of_expectations,
                                     feedback_style, perceived_supportiveness,
                                     decision_making_style, organization_and_planning_style,
                                     delegation_style, perceived_professional_demeanor,
                                     overall_working_experience,
                                     role_basis, attribution_basis, role_required,
                                     withdrawn_role_id, disposition, gate_eligible, live_since)
                -- live_since NULL EXPLICITLY. The column defaults to now(), and
                -- reviews_state_coherent (V60) requires it to be null for anything not live, so
                -- omitting it makes a held rating impossible to insert - the constraint reports
                -- the violation without naming the default that caused it.
                VALUES ($1,'Anonymous User',4.0,'Dispatch Lead','Halverson Logistics',
                        4,4,4,4,4,4,4,4,4,4,
                        'withdrawn','withdrawn_role',FALSE,$2,'held',FALSE,NULL)
                RETURNING id
                """).execute(Tuple.of(managerId, roleId))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    @AfterAll
    void shutdown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (pool   != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (vertx  != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // ── the journey ──────────────────────────────────────────────────────────

    /**
     * No credential at all is 401, from the security handler and not from the service.
     *
     * <p>This is the assertion that proves the operation is actually secured in the spec. A
     * {@code security:} block missing from one operation is invisible to every other suite: the
     * service still calls {@code requireAdmin} and still refuses, so a service test passes - with a
     * 403 rather than a 401, having first put an unauthenticated request through the handler.
     */
    @Test @Order(1)
    void withoutACredentialTheQueueIsUnauthorized() throws Exception {
        assertEquals(401, get("/api/admin/moderation/summary", null).statusCode());
        assertEquals(401, get("/api/admin/moderation/held-ratings", null).statusCode());
    }

    /** A real session belonging to an ordinary account reaches the service and is refused there. */
    @Test @Order(2)
    void anOrdinaryAccountsRealSessionIsForbidden() throws Exception {
        assertEquals(403, get("/api/admin/moderation/summary", ordinarySession).statusCode());
        assertEquals(403, get("/api/admin/moderation/held-ratings", ordinarySession).statusCode());
        assertEquals(403, post("/api/admin/moderation/held-ratings/" + reviewId + "/publish",
                               ordinarySession).statusCode());
    }

    @Test @Order(3)
    void theAdminsSessionReachesTheQueueAndTheQueueDescribesTheBacklog() throws Exception {
        HttpResponse<Buffer> res = get("/api/admin/moderation/summary", adminSession);
        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject summary = res.bodyAsJsonObject();
        assertEquals(1, (int) summary.getInteger("pendingEmployments"));
        assertEquals(1, (int) summary.getInteger("heldRatings"));
        assertNotNull(summary.getString("oldestHeldRatingAt"),
            "the age, because a count alone hides a rating nobody has looked at for six weeks");
    }

    @Test @Order(4)
    void theHeldQueueNamesTheRatingTheReasonAndTheActionOverHttp() throws Exception {
        JsonArray held = heldRatings();
        assertEquals(1, held.size(), held.encode());

        JsonObject row = held.getJsonObject(0);
        assertEquals(reviewId.toString(), row.getString("reviewId"));
        assertEquals("Priya Raghunathan", row.getString("managerName"));
        assertTrue(row.getString("whyHeld").contains("proposed"), row.getString("whyHeld"));
        assertEquals("Accept that employment.", row.getString("actionToRelease"));
        assertEquals(false, row.getBoolean("wouldPublishNow"));
    }

    /** Narrowed to one manager: the query the manager's own profile page makes. */
    @Test @Order(5)
    void theQueueCanBeNarrowedToOneManagerOverHttp() throws Exception {
        HttpResponse<Buffer> mine = get(
            "/api/admin/moderation/held-ratings?managerId=" + managerId, adminSession);
        assertEquals(200, mine.statusCode(), mine.bodyAsString());
        assertEquals(1, mine.bodyAsJsonObject().getJsonArray("data").size());

        HttpResponse<Buffer> somebodyElse = get(
            "/api/admin/moderation/held-ratings?managerId=" + (managerId + 9_999), adminSession);
        assertEquals(200, somebodyElse.statusCode());
        assertEquals(0, somebodyElse.bodyAsJsonObject().getJsonArray("data").size());
    }

    /**
     * Publishing is refused while the employment is still proposed, as a 409 and not a 500.
     *
     * <p>The status code is the point. A domain refusal arriving as a 500 is indistinguishable from
     * the server falling over, and the client cannot tell the moderator anything useful about it.
     */
    @Test @Order(6)
    void publishingIsRefusedAsAConflictWhileSomethingStillHoldsIt() throws Exception {
        HttpResponse<Buffer> res =
            post("/api/admin/moderation/held-ratings/" + reviewId + "/publish", adminSession);
        assertEquals(409, res.statusCode(), res.bodyAsString());
        /*
          "message", NOT "error". ManagersHandler.handleError serialises a ServiceException as
          {"message": ...}; only the security handler's own refusals use {"error": ...}. This
          assertion is the one that caught the client reading the wrong key and therefore showing a
          generic failure in place of the server's explanation - which is exactly the kind of break
          that passes a service test, passes a mocked browser test, and reaches a moderator.
        */
        assertNotNull(res.bodyAsJsonObject().getString("message"),
            "and it says why, in the body, rather than in a log the moderator cannot read");
        assertEquals("held", dispositionOf(reviewId), "and the rating is untouched");
    }

    /** A malformed id is 400 from the spec's own validation, before any handler runs. */
    @Test @Order(7)
    void aMalformedRatingIdIsRejectedByTheContract() throws Exception {
        assertEquals(400,
            post("/api/admin/moderation/held-ratings/not-a-uuid/publish", adminSession).statusCode());
    }

    /**
     * Accepting the employment releases the rating, which is the whole journey's point.
     *
     * <p>Asserted on the database and on the queue, because they are different claims: the row
     * went live, and the screen a moderator is looking at now agrees.
     */
    @Test @Order(8)
    void acceptingTheEmploymentReleasesTheRatingAndEmptiesTheQueue() throws Exception {
        HttpResponse<Buffer> res =
            post("/api/admin/moderation/employments/" + roleId + "/accept", adminSession);
        assertEquals(200, res.statusCode(), res.bodyAsString());

        assertEquals("live", dispositionOf(reviewId),
            "the employment it was waiting on is accepted, so it is public");
        assertEquals(0, heldRatings().size(), "and it has left the queue");

        Integer cached = await(pool.preparedQuery(
                "SELECT reviews_count FROM managers WHERE id = $1").execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getInteger("reviews_count")));
        assertEquals(1, cached, "and the number on the profile moved with it");
    }

    /** Publishing it now says it is already live, rather than reporting a second success. */
    @Test @Order(9)
    void publishingTheAlreadyReleasedRatingIsAConflict() throws Exception {
        HttpResponse<Buffer> res =
            post("/api/admin/moderation/held-ratings/" + reviewId + "/publish", adminSession);
        assertEquals(409, res.statusCode(), res.bodyAsString());
        assertTrue(res.bodyAsJsonObject().getString("message").contains("already live"),
            res.bodyAsString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonArray heldRatings() throws Exception {
        HttpResponse<Buffer> res = get("/api/admin/moderation/held-ratings", adminSession);
        assertEquals(200, res.statusCode(), res.bodyAsString());
        return res.bodyAsJsonObject().getJsonArray("data");
    }

    /** The cookie a browser sends, by the name the server reads. */
    private HttpResponse<Buffer> get(String path, String session) throws Exception {
        var req = client.get(path);
        if (session != null) req.putHeader("Cookie", "__Host-wp_session=" + session);
        return req.send().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private HttpResponse<Buffer> post(String path, String session) throws Exception {
        var req = client.post(path);
        if (session != null) req.putHeader("Cookie", "__Host-wp_session=" + session);
        return req.sendJsonObject(new JsonObject())
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private String dispositionOf(UUID id) throws Exception {
        return await(pool.preparedQuery("SELECT disposition FROM reviews WHERE id = $1")
            .execute(Tuple.of(id)).map(rs -> rs.iterator().next().getString("disposition")));
    }

    private UUID insertUser(String auth0Id, String username, String role) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Test','User',$4) RETURNING id")
            .execute(Tuple.of(auth0Id, auth0Id + "@test.com", username, role))
            .map(rs -> rs.iterator().next().getUUID("id")));
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

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
