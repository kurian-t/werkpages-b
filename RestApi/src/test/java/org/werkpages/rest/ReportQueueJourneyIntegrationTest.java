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
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.SessionRepository;
import org.werkpages.repository.SessionRepository.Product;
import org.werkpages.repository.UserRepository;
import org.werkpages.rest.handlers.ReportsHandler;
import org.werkpages.service.AdminService;
import org.werkpages.service.ReportService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ONE REPORT, FROM THE BUTTON TO AN ADMIN RESOLVING IT, OVER REAL HTTP.
 *
 * <h2>The bug this file exists for</h2>
 *
 * <p>The Report button worked and the report went nowhere. {@code POST /api/managers/{id}/report}
 * validated its reason, refused a duplicate and wrote a row to {@code reports} - and the only
 * {@code SELECT} against that table anywhere in either backend was the duplicate check itself.
 * No admin endpoint read it, no admin screen listed it, nothing notified anybody. The toast said
 * "Thank you. Our team will review this profile," and no team could: there was no screen on which
 * a report had ever been visible. The table has existed since V1 with no status column, which is
 * the tell - nothing that drains was ever built.
 *
 * <p>It mattered more than an unbuilt admin screen usually would, because the Report button is the
 * correction route the Terms of Service and the Privacy Policy both point a rated person at. A
 * named individual disputing their own profile was being sent to a dead end.
 *
 * <h2>Why the test is at this layer</h2>
 *
 * <p>Every layer below this one was already green. {@code ReportServiceTest} mocks the repository,
 * {@code ReportServiceIntegrationTest} drives the service against a real database, and both pass
 * while nobody can read a report, because neither asks whether anything downstream exists. A
 * Playwright spec cannot see it either: it mocks the API, so a queue endpoint that was never built
 * returns whatever the mock says. Only the chain shows it, which is the lesson of issue 26 and the
 * reason {@link AdminModerationJourneyIntegrationTest} is shaped the same way.
 *
 * <p>So what is real here is what that suite makes real: the HTTP server and client, the router
 * built from the shipped {@code openapi.yaml} by the same factory {@code MainVerticle} boots with
 * and with its security handlers required, the real {@link RequestAuthenticator}, the real
 * {@link SessionRepository} against a real PostgreSQL, the real {@link ReportsHandler},
 * {@link ReportService} and {@link AdminService}, and the real schema migrated by Flyway from the
 * shared Werkpages stream.
 *
 * <p><b>Named {@code ...IntegrationTest} deliberately.</b> Surefire claims {@code *Test} and runs
 * it in the unit phase, where {@code DOCKER_HOST} is unset, and a Testcontainers test there fails
 * with "Could not find a valid Docker environment". See {@code RestApi/pom.xml}.
 *
 * <h2>Why it is ordered</h2>
 *
 * <p>It is one journey against one fixture, in the order it happens: a guest refused, a signed-in
 * account's report accepted, the same account refused a second one, the queue closed to a guest
 * and to an ordinary account, the admin reading the report with its reason and comment intact, the
 * admin resolving it, and the queue emptying. The last two are the assertions that would have
 * failed for the whole life of the feature.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReportQueueJourneyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("report_queue").withUsername("test").withPassword("test");

    private static final String CRON_SECRET = "cron-secret-not-under-test-here";

    static Vertx      vertx;
    static Pool       pool;
    static HttpServer server;
    static WebClient  client;

    /** Genuinely minted sessions, exactly as the OAuth callback mints them. */
    static String adminSession;
    static String ordinarySession;

    static long managerId;
    static long companyId;

    /** The report created over HTTP in the second test, carried to the admin tests. */
    static String reportId;

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

        /*
          The admin-aware ReportService, which is the constructor MainVerticle uses.

          The two-argument one still exists for the submit path's own tests, and an instance built
          that way refuses every admin operation rather than running one unauthorized - the same
          null-collaborator guard ModerationService carries. Building the admin-aware one here is
          part of the assertion: if MainVerticle were left on the two-argument constructor, the
          queue would answer 403 to a genuine admin and this suite would say so.
        */
        ReportsHandler handler = new ReportsHandler(
            new ReportService(users, new ReportRepository(pool), admin));

        SessionRepository sessions = new SessionRepository(pool, Product.WERKPAGES);

        // The router from the SHIPPED spec with security handlers required, so an operation whose
        // `security:` block was forgotten cannot pass this suite while being open in production.
        CompletableFuture<Router> built = new CompletableFuture<>();
        OpenAPI3RouterFactory.create(vertx, "openapi.yaml", ar -> {
            if (ar.failed()) { built.completeExceptionally(ar.cause()); return; }
            OpenAPI3RouterFactory rf = ar.result();
            rf.setOptions(new RouterFactoryOptions().setMountNotImplementedHandler(true));
            rf.addSecurityHandler("bearerAuth",
                new RequestAuthenticator(CRON_SECRET, sessions::touch, null));
            rf.addHandlerByOperationId("reportManager",      handler::handleReportManager);
            rf.addHandlerByOperationId("adminListReports",   handler::handleListReports);
            rf.addHandlerByOperationId("adminResolveReport", handler::handleResolveReport);
            built.complete(rf.getRouter());
        });
        Router router = built.get(60, TimeUnit.SECONDS);

        server = vertx.createHttpServer().requestHandler(router).listen(0)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        client = WebClient.create(vertx, new WebClientOptions()
            .setDefaultHost("127.0.0.1").setDefaultPort(server.actualPort()));

        UUID adminId    = insertUser("auth0|report-admin",    "reportadmin",    "admin");
        UUID ordinaryId = insertUser("auth0|report-ordinary", "reportordinary", "user");
        adminSession    = await(sessions.mint(adminId,    "ReportQueueTest/1.0", new byte[32]));
        ordinarySession = await(sessions.mint(ordinaryId, "ReportQueueTest/1.0", new byte[32]));

        companyId = await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES "
              + "('Thorne Freight','ghost','thorne-freight') RETURNING id")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        managerId = await(pool.preparedQuery(
                "INSERT INTO managers(name,company,company_id,title,image,status,approval_status,"
              + "overall_rating,reviews_count,category_averages,slug) "
              + "VALUES ('Ingrid Solberg','Thorne Freight',$1,'Depot Manager','img',"
              + "'active','approved',NULL,0,'{}','ingrid-solberg') RETURNING id")
            .execute(Tuple.of(companyId)).map(rs -> rs.iterator().next().getLong("id")));
    }

    @AfterAll
    void shutdown() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (pool   != null) pool.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (vertx  != null) vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // ── submitting ───────────────────────────────────────────────────────────

    /**
     * A guest is refused by the security handler, before any validation runs.
     *
     * <p>This is the submit path's intended behaviour and not a defect: the profile page keeps the
     * half-written report in {@code sessionStorage}, sends the visitor to sign up, and posts it on
     * the way back, which is the capture section 44 requires. The assertion is here because the
     * published contract did not mention a 401 at all, so nothing recorded that the endpoint is
     * closed to guests or would notice if it silently opened.
     */
    @Test @Order(1)
    void aGuestsReportIsRefusedBeforeValidation() throws Exception {
        HttpResponse<Buffer> res = post("/api/managers/" + managerId + "/report", null,
            new JsonObject().put("reason", "incorrect_person"));

        assertEquals(401, res.statusCode(), res.bodyAsString());
        assertEquals(0, countReports(), "nothing is written for a request that never authenticated");
    }

    @Test @Order(2)
    void aSignedInAccountsReportIsAcceptedAndCarriesItsReasonAndComment() throws Exception {
        HttpResponse<Buffer> res = post("/api/managers/" + managerId + "/report", ordinarySession,
            new JsonObject().put("reason", "incorrect_person")
                            .put("comment", "This is a different Ingrid Solberg."));

        assertEquals(201, res.statusCode(), res.bodyAsString());
        reportId = res.bodyAsJsonObject().getString("reportId");
        assertNotNull(reportId, "the caller is told which report it filed");
        assertEquals(1, countReports());
    }

    /** The 409 the profile page turns into "You have already flagged this profile." */
    @Test @Order(3)
    void theSameAccountCannotReportTheSameProfileTwice() throws Exception {
        HttpResponse<Buffer> res = post("/api/managers/" + managerId + "/report", ordinarySession,
            new JsonObject().put("reason", "duplicate_profile"));

        assertEquals(409, res.statusCode(), res.bodyAsString());
        assertEquals(1, countReports(), "and the second one is not written");
    }

    // ── the queue, which is what was missing ─────────────────────────────────

    @Test @Order(4)
    void theQueueIsClosedToAGuestAndToAnOrdinaryAccount() throws Exception {
        // 401 from the security handler, 403 from the service: two different refusals, and
        // collapsing them would hide an operation whose spec forgot its security block.
        assertEquals(401, get("/api/admin/reports", null).statusCode());
        assertEquals(403, get("/api/admin/reports", ordinarySession).statusCode());
        assertEquals(403, post("/api/admin/reports/" + reportId + "/resolve", ordinarySession,
                               new JsonObject().put("action", "dismissed")).statusCode());
    }

    /**
     * THE ASSERTION THAT WOULD HAVE FAILED FOR THE WHOLE LIFE OF THE FEATURE.
     *
     * <p>An admin reads the open queue and finds the report with everything needed to act on it:
     * which profile, what was alleged, what the reporter wrote, and when. Before this change there
     * was no endpoint to call, so there was no way to discover that reports were accumulating
     * unread.
     */
    @Test @Order(5)
    void theAdminSeesTheOpenReportWithTheProfileItConcerns() throws Exception {
        HttpResponse<Buffer> res = get("/api/admin/reports", adminSession);
        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject body = res.bodyAsJsonObject();
        assertEquals(1, (int) body.getInteger("total"), body.encode());

        JsonArray data = body.getJsonArray("data");
        assertEquals(1, data.size(), body.encode());
        JsonObject report = data.getJsonObject(0);

        assertEquals(reportId, report.getString("id"));
        assertEquals(managerId, (long) report.getLong("managerId"));
        assertEquals("Ingrid Solberg", report.getString("managerName"),
            "the name, because a queue of manager ids cannot be worked");
        assertEquals("ingrid-solberg", report.getString("managerSlug"),
            "the slug, so the admin can open the profile the report is about");
        assertEquals("incorrect_person", report.getString("reason"));
        assertEquals("This is a different Ingrid Solberg.", report.getString("comment"));
        assertEquals("open", report.getString("status"));
        assertEquals("reportordinary", report.getString("reporter"),
            "who filed it, so a pattern of reports from one account is visible");
        assertNotNull(report.getString("createdAt"),
            "the age, because a count alone hides a report nobody has looked at for six weeks");
    }

    /**
     * Resolving drains the queue and records who resolved it.
     *
     * <p>A queue that cannot be cleared is a queue that gets ignored, which is why the resolution
     * state is a column rather than a convention. The row stays: a report is evidence about a
     * profile and about the account that filed it, and both outlive the decision.
     */
    @Test @Order(6)
    void resolvingTheReportDrainsTheQueueAndRecordsWhoDidIt() throws Exception {
        HttpResponse<Buffer> res = post("/api/admin/reports/" + reportId + "/resolve", adminSession,
            new JsonObject().put("action", "resolved").put("note", "Profile corrected."));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        assertEquals(0, (int) get("/api/admin/reports", adminSession)
            .bodyAsJsonObject().getInteger("total"), "the open queue is empty");
        assertEquals(1, countReports(), "and the report itself is kept");

        JsonObject stored = reportRow(reportId);
        assertEquals("resolved", stored.getString("status"));
        assertEquals("Profile corrected.", stored.getString("resolution_note"));
        assertNotNull(stored.getString("resolved_at"));
        assertNotNull(stored.getString("resolved_by"), "the admin who decided, by id");

        // Still readable when asked for by state, so a decision can be reviewed later.
        JsonObject resolved = get("/api/admin/reports?status=resolved", adminSession)
            .bodyAsJsonObject();
        assertEquals(1, (int) resolved.getInteger("total"), resolved.encode());
    }

    @Test @Order(7)
    void aSecondResolutionOfTheSameReportIsRefused() throws Exception {
        // Reporting success for a decision that changed nothing is how a report gets decided
        // twice, the second time by somebody who cannot tell the first decision happened.
        HttpResponse<Buffer> res = post("/api/admin/reports/" + reportId + "/resolve", adminSession,
            new JsonObject().put("action", "dismissed"));

        assertEquals(409, res.statusCode(), res.bodyAsString());
        assertEquals("resolved", reportRow(reportId).getString("status"),
            "and the first decision stands");
    }

    @Test @Order(8)
    void resolvingAReportThatDoesNotExistIsANotFound() throws Exception {
        HttpResponse<Buffer> res = post(
            "/api/admin/reports/" + UUID.randomUUID() + "/resolve", adminSession,
            new JsonObject().put("action", "dismissed"));

        assertEquals(404, res.statusCode(), res.bodyAsString());
    }

    @Test @Order(9)
    void anUnknownActionIsRefusedByTheContract() throws Exception {
        // `action` is an enum in the spec, so a typo must never reach the service.
        HttpResponse<Buffer> res = post("/api/admin/reports/" + reportId + "/resolve", adminSession,
            new JsonObject().put("action", "sort-of-handled"));

        assertEquals(400, res.statusCode(), res.bodyAsString());
    }

    /**
     * A report whose reporter deleted their account is still in the queue, with no reporter.
     *
     * <p>{@code reports.user_id} is {@code ON DELETE SET NULL}, so account deletion leaves the
     * report standing and anonymous. The queue has to answer for that row rather than drop it or
     * fail on it: the allegation about the profile is still worth looking at once the account that
     * made it is gone, and a join that assumed a user would have hidden it.
     */
    @Test @Order(10)
    void aReportLeftBehindByADeletedAccountStaysInTheQueueWithoutAReporter() throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO reports (manager_id, user_id, reason, comment) "
              + "VALUES ($1, NULL, 'never_worked_here', 'Left behind by a deleted account.')")
            .execute(Tuple.of(managerId)));

        JsonObject body = get("/api/admin/reports", adminSession).bodyAsJsonObject();
        assertEquals(1, (int) body.getInteger("total"), body.encode());

        JsonObject report = body.getJsonArray("data").getJsonObject(0);
        assertEquals("never_worked_here", report.getString("reason"));
        assertNull(report.getString("reporter"), "nobody to name, and the row is still served");
        /*
          `reporterKnown`, not `reporterDeleted`, because the schema cannot tell those apart: a
          null user_id means the account was deleted OR that the report was filed with a
          credential that had no user row behind it. The queue says only what it can defend,
          which is whether there is an account to look at.
        */
        assertEquals(Boolean.FALSE, report.getBoolean("reporterKnown"),
            "no account stands behind it, and the queue says so rather than guessing why");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpResponse<Buffer> get(String path, String session) throws Exception {
        var req = client.get(path);
        if (session != null) req.putHeader("Cookie", "__Host-wp_session=" + session);
        return req.send().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private HttpResponse<Buffer> post(String path, String session, JsonObject body) throws Exception {
        var req = client.post(path);
        if (session != null) req.putHeader("Cookie", "__Host-wp_session=" + session);
        return req.sendJsonObject(body)
            .toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private int countReports() throws Exception {
        return await(pool.query("SELECT count(*) AS n FROM reports").execute()
            .map(rs -> rs.iterator().next().getInteger("n")));
    }

    /** The stored row, as strings, so an assertion reads the database and not the response. */
    private JsonObject reportRow(String id) throws Exception {
        return await(pool.preparedQuery(
                "SELECT status, resolution_note, resolved_at::text AS resolved_at, "
              + "resolved_by::text AS resolved_by FROM reports WHERE id = $1")
            .execute(Tuple.of(UUID.fromString(id)))
            .map(rs -> {
                var row = rs.iterator().next();
                return new JsonObject()
                    .put("status",          row.getString("status"))
                    .put("resolution_note", row.getString("resolution_note"))
                    .put("resolved_at",     row.getString("resolved_at"))
                    .put("resolved_by",     row.getString("resolved_by"));
            }));
    }

    private UUID insertUser(String auth0Id, String username, String role) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Test','User',$4) RETURNING id")
            .execute(Tuple.of(auth0Id, auth0Id + "@test.com", username, role))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /**
     * This repository's own migrations, which ARE the shared stream: Werkpages owns it.
     *
     * <p>{@code PLATFORM_MIGRATIONS_DIR} still wins when set, so a job that stages the migrations
     * somewhere else is honoured, exactly as {@code AdminModerationJourneyIntegrationTest} does it.
     */
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
}
