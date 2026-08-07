package org.ratemymanager.integration;

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
import org.ratemymanager.repository.CompanyRepository;
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.NotificationRepository;
import org.ratemymanager.repository.ReportRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the conditional ghost seed-review handling in doAttachToExisting:
 *
 *   Non-contributor rates ghost → seed deleted immediately, real rating shows at once.
 *   Contributor     rates ghost → seed kept on 14-day expiry counter, both reviews coexist.
 */
@Testcontainers
class GhostSeedDeletionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    service;
    static ManagerRepository managerRepo;
    static ReviewRepository  reviewRepo;
    static CompanyRepository companyRepo;
    static UserRepository    userRepo;

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

        pool        = PgPool.pool(connectOptions, new PoolOptions().setMaxSize(5));
        userRepo    = new UserRepository(pool);
        managerRepo = new ManagerRepository(pool);
        reviewRepo  = new ReviewRepository(pool);
        companyRepo = new CompanyRepository(pool);

        service = new ManagerService(managerRepo, reviewRepo, userRepo,
            new EditRepository(pool), new ReportRepository(pool), pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── Non-contributor rates ghost → seed deleted immediately ───────────────

    @Test
    void nonContributor_ratesGhostManager_seedDeletedImmediately() throws Exception {
        // 1. User A creates a ghost manager via /find (first-ever search, long names).
        String userAAuth0 = insertUser("auth0|ghost-creator", "GhostCreator");
        await(service.findOrCreate(userAAuth0, "Emma", "Davis", "Senior Manager", "SeedDeleteCo", "US", null, null, null));

        long ghostId = fetchManagerId("Emma Davis", "SeedDeleteCo");
        assertEquals(1, countSeedReviews(ghostId), "Ghost must have exactly 1 seed review before any real rating");
        assertEquals(1, countTotalReviews(ghostId));

        // 2. User B (non-contributor, 0 prior reviews) rates the same manager via the add-manager form.
        String userBAuth0 = insertUser("auth0|non-contributor", "NonContributor");
        await(service.createManager(userBAuth0, validCreateManagerBody("Emma Davis", "SeedDeleteCo", "Senior Manager"), null));
        Thread.sleep(1_000); // allow fire-and-forget recalculate

        // 3. Seed must be gone — only user B's real review remains.
        assertEquals(0, countSeedReviews(ghostId),
            "Seed review must be deleted immediately when a non-contributor rates the ghost manager");
        assertEquals(1, countTotalReviews(ghostId),
            "Only the non-contributor's real review must remain");

        // 4. Rating must reflect the real submitted value, not 0.
        Row manager = fetchManager(ghostId);
        assertNotNull(manager.getBigDecimal("overall_rating"),
            "overall_rating must be set after real review and recalculate");
        assertTrue(manager.getBigDecimal("overall_rating").doubleValue() > 0,
            "overall_rating must be > 0 after recalculate");
    }

    // ── Contributor rates ghost → seed kept on 14-day expiry counter ─────────

    @Test
    void contributor_ratesGhostManager_seedScheduledForExpiry() throws Exception {
        // 1. Create a ghost manager directly (avoids consuming userA's ghost slot for a second test).
        Row companyRow = await(companyRepo.findOrCreate("ExpiryCo", null, null));
        Row ghostRow = await(managerRepo.createAutoApproved(
            "Frank Miller", "ExpiryCo", "Director", "US", null, null, null, null, companyRow.getLong("id")));
        long ghostId = ghostRow.getLong("id");
        await(reviewRepo.createSeedReview(ghostId, "ExpiryCo", "Director"));
        Thread.sleep(500); // let seed recalculate settle

        assertEquals(1, countSeedReviews(ghostId), "Ghost must have 1 seed review");

        // 2. Create user C and give them a prior review on a different manager (makes them a contributor).
        String userCAuth0 = insertUser("auth0|contributor", "ContribUser");
        UUID userCId = findUserId(userCAuth0);

        Row priorCompany = await(companyRepo.findOrCreate("PriorCo", null, null));
        Row priorManager = await(managerRepo.createAutoApproved(
            "Prior Manager", "PriorCo", "Manager", "US", null, null, null, null, priorCompany.getLong("id")));
        long priorManagerId = priorManager.getLong("id");

        // Insert the prior review directly — user C has now contributed once.
        await(pool.preparedQuery("""
                INSERT INTO reviews (
                    manager_id, user_id, author, overall_rating,
                    communication_style, perceived_approachability, perceived_clarity_of_expectations,
                    feedback_style, perceived_supportiveness, decision_making_style,
                    organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                    overall_working_experience, manager_company, manager_title,
                    worked_from, verified, helpful_count, weight, created_at, updated_at
                )
                VALUES ($1, $2, 'PriorAuthor', 4.0,
                        4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
                        'PriorCo', 'Manager', '2023-01-01',
                        true, 0, false, now(), now())
                """)
            .execute(Tuple.of(priorManagerId, userCId)));

        // 3. Contributor (user C) rates the ghost manager via the add-manager form.
        await(service.createManager(userCAuth0,
            validCreateManagerBody("Frank Miller", "ExpiryCo", "Director"), null));
        Thread.sleep(1_000); // allow fire-and-forget scheduleSeedExpiry / recalculate

        // 4. Both reviews must still exist (seed + user C's real review).
        assertEquals(1, countSeedReviews(ghostId),
            "Seed review must NOT be deleted when a contributor rates the ghost manager");
        assertEquals(2, countTotalReviews(ghostId),
            "Both seed and contributor's real review must coexist");

        // 5. Seed must now have weight_expires_on set (14-day countdown started).
        RowSet<Row> seedRows = await(pool.preparedQuery(
            "SELECT weight_expires_on FROM reviews WHERE manager_id = $1 AND weight = TRUE AND user_id IS NULL")
            .execute(Tuple.of(ghostId)));
        assertTrue(seedRows.iterator().hasNext(), "Seed review row must still exist");
        assertNotNull(seedRows.iterator().next().getLocalDate("weight_expires_on"),
            "weight_expires_on must be set on the seed review after a contributor rates the ghost");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, auth0Id + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id)));
        return rs.iterator().next().getUUID("id");
    }

    private long fetchManagerId(String name, String company) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery(
            "SELECT id FROM managers WHERE name = $1 AND company = $2")
            .execute(Tuple.of(name, company)));
        return rs.iterator().next().getLong("id");
    }

    private Row fetchManager(long managerId) throws Exception {
        return await(pool.preparedQuery(
            "SELECT id, overall_rating, reviews_count FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)))
            .iterator().next();
    }

    private int countSeedReviews(long managerId) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery(
            "SELECT COUNT(*) AS cnt FROM reviews WHERE manager_id = $1 AND weight = TRUE AND user_id IS NULL AND deleted_at IS NULL")
            .execute(Tuple.of(managerId)));
        return rs.iterator().next().getInteger("cnt");
    }

    private int countTotalReviews(long managerId) throws Exception {
        RowSet<Row> rs = await(pool.preparedQuery(
            "SELECT COUNT(*) AS cnt FROM reviews WHERE manager_id = $1 AND deleted_at IS NULL")
            .execute(Tuple.of(managerId)));
        return rs.iterator().next().getInteger("cnt");
    }

    private static JsonObject validCreateManagerBody(String name, String company, String title) {
        String[] parts  = name.split(" ", 2);
        String firstName = parts[0];
        String lastName  = parts.length > 1 ? parts[1] : "X";
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
            .put("firstName", firstName)
            .put("lastName",  lastName)
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
