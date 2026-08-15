package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that isContributor() correctly determines contribution status,
 * which is the authoritative input for the server-side categoryAverages gate.
 *
 * The gating logic itself lives in ManagersHandler:
 *   if (!contributed) json.put("categoryAverages", new JsonObject());
 *
 * These tests ensure the boolean driving that decision is always correct.
 */
@Testcontainers
class ContributionGateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool           pool;
    static ManagerService service;

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

        UserRepository    userRepo    = new UserRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        ManagerRepository managerRepo = new ManagerRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        pool.query("TRUNCATE managers, users CASCADE").execute()
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── isContributor ─────────────────────────────────────────────────────────

    @Test
    void isContributor_nullAuth0Id_returnsFalse() throws Exception {
        assertFalse(await(service.isContributor(null)));
    }

    @Test
    void isContributor_unknownAuth0Id_returnsFalse() throws Exception {
        // User does not exist in the database
        assertFalse(await(service.isContributor("auth0|no-such-user")));
    }

    @Test
    void isContributor_userWithNoReviews_returnsFalse() throws Exception {
        String auth0Id = insertUser("auth0|no-reviews", "NoReviewsUser01");
        assertFalse(await(service.isContributor(auth0Id)));
    }

    @Test
    void isContributor_userWithOneReview_returnsTrue() throws Exception {
        String auth0Id = insertUser("auth0|one-review", "OneReviewUser01");
        long   managerId = insertManager("Gate Test Manager", "GateCorp", "Engineer");

        await(service.createReview(auth0Id, managerId, reviewBody(), null));

        assertTrue(await(service.isContributor(auth0Id)));
    }

    @Test
    void isContributor_userWithMultipleReviews_returnsTrue() throws Exception {
        String auth0Id  = insertUser("auth0|multi-review", "MultiReviewUser01");
        long   managerA = insertManager("Gate Mgr A", "CorpA", "Lead");
        long   managerB = insertManager("Gate Mgr B", "CorpB", "Manager");

        await(service.createReview(auth0Id, managerA, reviewBody(), null));
        await(service.createReview(auth0Id, managerB, reviewBody(), null));

        assertTrue(await(service.isContributor(auth0Id)));
    }

    @Test
    void isContributor_differentUsersAreIndependent() throws Exception {
        String contributor    = insertUser("auth0|gate-contrib",    "GateContrib01");
        String nonContributor = insertUser("auth0|gate-noncontrib", "GateNonContrib01");
        long   managerId      = insertManager("Independence Mgr", "IndepCorp", "Lead");

        await(service.createReview(contributor, managerId, reviewBody(), null));

        assertTrue(await(service.isContributor(contributor)));
        assertFalse(await(service.isContributor(nonContributor)));
    }

    // ── Company profile — categoryAverages present for service layer ──────────
    // The service always returns full data; the handler strips it for non-contributors.
    // These tests verify the service contract hasn't changed.

    @Test
    void getCompanyProfile_serviceAlwaysReturnsCategoryAverages() throws Exception {
        insertCompany("GateCo", "approved");
        String auth0Id   = insertUser("auth0|gateprofile", "GateProfile01");
        long   managerId = insertManagerForCompany("Profile Mgr", "GateCo", "Engineer");

        await(service.createReview(auth0Id, managerId, reviewBody(), null));

        JsonObject result = await(service.getCompanyProfile("GateCo"));

        // Service always includes the key; handler zeroes it for non-contributors
        assertTrue(result.containsKey("categoryAverages"),
            "Service must always return categoryAverages key; handler is responsible for gating");
    }

    @Test
    void getCompanyBySlug_serviceAlwaysReturnsCategoryAverages() throws Exception {
        insertCompanyWithSlug("SlugCo", "slug-co", "approved");
        String auth0Id   = insertUser("auth0|gateslug", "GateSlug01");
        long   managerId = insertManagerForCompany("Slug Mgr", "SlugCo", "Engineer");

        await(service.createReview(auth0Id, managerId, reviewBody(), null));

        JsonObject result = await(service.getCompanyBySlug("slug-co"));

        assertTrue(result.containsKey("categoryAverages"),
            "Service must always return categoryAverages key; handler is responsible for gating");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String insertUser(String auth0Id, String username) throws Exception {
        pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User"))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        return auth0Id;
    }

    private long insertManager(String name, String company, String title) throws Exception {
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private long insertManagerForCompany(String name, String company, String title) throws Exception {
        long companyId = pool.preparedQuery("SELECT id FROM companies WHERE LOWER(TRIM(name)) = LOWER(TRIM($1))")
            .execute(Tuple.of(company))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
        return pool.preparedQuery(
                "INSERT INTO managers(name, company, title, image, status, approval_status, company_id, " +
                "overall_rating, reviews_count, category_averages) " +
                "VALUES ($1,$2,$3,'img','active','approved',$4,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, companyId))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            .iterator().next().getLong("id");
    }

    private void insertCompany(String name, String status) throws Exception {
        pool.preparedQuery(
                "INSERT INTO companies(name, status, created_at, updated_at) VALUES ($1,$2,now(),now())")
            .execute(Tuple.of(name, status))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private void insertCompanyWithSlug(String name, String slug, String status) throws Exception {
        pool.preparedQuery(
                "INSERT INTO companies(name, slug, status, created_at, updated_at) VALUES ($1,$2,$3,now(),now())")
            .execute(Tuple.of(name, slug, status))
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static JsonObject reviewBody() {
        return new JsonObject()
            .put("overallRating", 4.0)
            .put("managerCompany", "GateCorp")
            .put("managerTitle", "Engineer")
            .put("workedFrom", "2022-01")
            .put("ratings", new JsonObject()
                .put("Communication Style", 4)
                .put("Perceived Approachability", 4)
                .put("Perceived Clarity of Expectations", 4)
                .put("Feedback Style", 4)
                .put("Perceived Supportiveness", 4)
                .put("Decision Making Style", 4)
                .put("Organization and Planning Style", 4)
                .put("Delegation Style", 4)
                .put("Perceived Professional Demeanor", 4)
                .put("Overall Working Experience", 4));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
