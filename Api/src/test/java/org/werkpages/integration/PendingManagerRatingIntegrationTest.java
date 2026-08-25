package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.NotificationRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.AdminService;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that:
 *  1. A newly created pending manager has NO cached rating (overall_rating IS NULL, reviews_count = 0).
 *     The user's rating must not leak onto the live site before admin approval.
 *  2. When an admin approves the manager, recalculate runs and the correct rating appears.
 */
@Testcontainers
class PendingManagerRatingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    managerService;
    static AdminService      adminService;
    static UserRepository    userRepo;
    static ManagerRepository managerRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions connectOptions = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));

        userRepo    = new UserRepository(pool);
        managerRepo = new ManagerRepository(pool);
        ReviewRepository       reviewRepo  = new ReviewRepository(pool);
        EditRepository         editRepo    = new EditRepository(pool);
        ReportRepository       reportRepo  = new ReportRepository(pool);
        NotificationRepository notifRepo   = new NotificationRepository(pool);
        CompanyRepository      companyRepo = new CompanyRepository(pool);

        managerService = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
        adminService   = new AdminService(userRepo, managerRepo, reviewRepo, editRepo, notifRepo, companyRepo, null);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── createManager must NOT set a rating on the pending manager ────────────

    @Test
    void createManager_pendingManager_hasNoRating() throws Exception {
        String auth0Id = insertUser("auth0|pending-rating", "PendingUser");

        Row result = await(managerService.createManager(auth0Id, validCreateManagerBody("Dana Lee", "WidgetCo", "Director"), null));
        long managerId = result.getLong("id");

        // Give background recalculate a moment to run (it must NOT run, but we wait just in case)
        Thread.sleep(1_000);

        Row manager = fetchManager(managerId);
        assertEquals("pending_approval", manager.getString("approval_status"));
        // overall_rating must not be set to the user's submitted rating —
        // the initial INSERT value is 0, and recalculate must NOT have run.
        double rating = manager.getBigDecimal("overall_rating") == null ? 0.0
            : manager.getBigDecimal("overall_rating").doubleValue();
        assertEquals(0.0, rating, 0.001,
            "Pending manager must have overall_rating = 0 — rating must not leak before approval");
        assertEquals(0, manager.getInteger("reviews_count"),
            "Pending manager must have reviews_count = 0 before approval");
    }

    // ── approvePendingManager must trigger recalculate so the rating appears ──

    @Test
    void approvePendingManager_triggersRatingRecalculation() throws Exception {
        String userAuth0Id  = insertUser("auth0|submitter", "Submitter");
        String adminAuth0Id = insertUser("auth0|admin-apr", "AdminUser");
        makeAdmin(adminAuth0Id);

        Row pendingRow = await(managerService.createManager(
            userAuth0Id, validCreateManagerBody("Eve Zhao", "BlueCo", "VP Engineering"), null));
        long managerId = pendingRow.getLong("id");

        // Confirm no rating yet — overall_rating stays at its initial 0, not the user's submitted value
        Row before = fetchManager(managerId);
        double ratingBefore = before.getBigDecimal("overall_rating") == null ? 0.0
            : before.getBigDecimal("overall_rating").doubleValue();
        assertEquals(0.0, ratingBefore, 0.001, "Must have overall_rating = 0 before approval");

        // Admin approves — recalculate should fire, giving the manager its real rating
        await(adminService.approvePendingManager(adminAuth0Id, managerId, null));

        // Background recalculate is fire-and-forget; wait briefly
        Thread.sleep(1_000);

        Row after = fetchManager(managerId);
        assertEquals("approved", after.getString("approval_status"));
        assertNotNull(after.getBigDecimal("overall_rating"),
            "Approved manager must have a rating computed from the submitted review");
        assertTrue(after.getInteger("reviews_count") > 0,
            "Approved manager must have reviews_count > 0");
    }

    // ── Adding + rating a manager must record the contribution (unlocks ratings) ─

    /**
     * Regression guard for the "still locked after rating" bug: the ratings lock reads
     * hasContributed(userId) = EXISTS(review with that user_id). Even though the created manager
     * is pending_approval, the submitted review must be persisted with the submitter's user_id so
     * hasContributed flips true and the site-wide ratings lock lifts. (The frontend mirrors this by
     * optimistically flipping user.hasContributed after a successful add+rate.)
     */
    @Test
    void createManager_withReview_recordsContribution() throws Exception {
        String auth0Id = insertUser("auth0|contrib", "ContribUser");
        UUID userId = await(userRepo.findIdByAuth0Id(auth0Id)).orElseThrow();

        assertFalse(await(userRepo.hasContributed(userId)),
            "user has not contributed before adding a manager");

        await(managerService.createManager(auth0Id,
            validCreateManagerBody("Casey Poe", "RivetCo", "Manager"), null));

        assertTrue(await(userRepo.hasContributed(userId)),
            "adding + rating a manager must record the contribution even while the manager is pending");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private void makeAdmin(String auth0Id) throws Exception {
        await(pool.preparedQuery("UPDATE users SET role = 'admin' WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id)));
    }

    private Row fetchManager(long managerId) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery(
            "SELECT id, approval_status, overall_rating, reviews_count FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)));
        return rs.iterator().next();
    }

    private static JsonObject validCreateManagerBody(String name, String company, String title) {
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        JsonObject review = new JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     "2022-01")
            .put("authorType",     "anonymous")
            .put("author",         "AnonTester99");
        return new JsonObject()
            .put("name",      name)
            .put("company",   company)
            .put("title",     title)
            .put("image",     "img")
            .put("country",   "US")
            .put("status",    "active")
            .put("startDate", "2020-01")
            .put("review",    review);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
