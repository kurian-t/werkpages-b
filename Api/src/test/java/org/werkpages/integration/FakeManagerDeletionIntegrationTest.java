package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.EditRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the fake manager deletion feature.
 *
 * Invariants verified:
 *  - Only seed_* managers are deleted; DEF14A_* (real scraped people) are never touched.
 *  - The manager with the fewest reviews is chosen first.
 *  - If no seed managers exist the method is a silent no-op.
 *  - Deletion cascades to reviews and career_history via ON DELETE CASCADE.
 *  - createManager triggers one deletion per successfully created manager.
 */
@Testcontainers
class FakeManagerDeletionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    service;
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
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── deleteFakeManagerInBackground ────────────────────────────────────────

    @Test
    void deleteFakeManager_onlyDeletesSeedPrefixed_notDEF14A() throws Exception {
        long seedId = insertSeedManager("seed_001", "Fake Felicia", "FakeCorp", "Manager");
        long govId  = insertSeedManager("DEF14A_001", "Real Regina", "GovCorp", "Director");

        managerRepo.deleteFakeManagerInBackground();
        Thread.sleep(1_000);

        assertFalse(managerExists(seedId), "seed_ manager should have been deleted");
        assertTrue(managerExists(govId),   "DEF14A_ manager must NOT be deleted");
    }

    @Test
    void deleteFakeManager_fewestReviewsFirst() throws Exception {
        long seedWithZero = insertSeedManager("seed_002", "Zero Reviews", "Corp", "Manager");
        long seedWithOne  = insertSeedManager("seed_003", "One Review",   "Corp", "Lead");

        String auth0Id = insertUser("auth0|del-order", "DelOrderUser");
        insertReviewDirect(seedWithOne, auth0Id);
        await(pool.preparedQuery("UPDATE managers SET reviews_count = 1 WHERE id = $1")
            .execute(Tuple.of(seedWithOne)));

        managerRepo.deleteFakeManagerInBackground();
        Thread.sleep(1_000);

        assertFalse(managerExists(seedWithZero), "0-review seed should be deleted first");
        assertTrue(managerExists(seedWithOne),   "1-review seed should survive this round");
    }

    @Test
    void deleteFakeManager_noSeedManagers_noError() throws Exception {
        // Empty DB — should silently succeed with no exception
        assertDoesNotThrow(() -> {
            managerRepo.deleteFakeManagerInBackground();
            Thread.sleep(500);
        });
    }

    @Test
    void deleteFakeManager_cascadesReviewsAndCareerHistory() throws Exception {
        long seedId    = insertSeedManager("seed_004", "Cascading Carl", "Corp", "Title");
        String auth0Id = insertUser("auth0|cascade", "CascadeUser");
        insertReviewDirect(seedId, auth0Id);
        insertCareerHistoryDirect(seedId);

        assertEquals(1L, countRelated("reviews",        "manager_id", seedId));
        assertEquals(1L, countRelated("career_history", "manager_id", seedId));

        managerRepo.deleteFakeManagerInBackground();
        Thread.sleep(1_000);

        assertFalse(managerExists(seedId));
        assertEquals(0L, countRelated("reviews",        "manager_id", seedId), "Reviews must cascade-delete");
        assertEquals(0L, countRelated("career_history", "manager_id", seedId), "Career history must cascade-delete");
    }

    @Test
    void deleteFakeManager_doesNotTouchApprovedUserSubmittedManagers() throws Exception {
        // external_id IS NULL → user-submitted; must never be deleted
        long userSubmittedId = await(pool
            .preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,
                                     overall_rating,reviews_count,category_averages)
                VALUES ('Real Person','Corp','Title','img','active','approved',0,0,'{}') RETURNING id
                """)
            .execute()
            .map(rs -> rs.iterator().next().getLong("id")));

        long seedId = insertSeedManager("seed_005", "Fake Fred", "FakeCo", "Manager");

        managerRepo.deleteFakeManagerInBackground();
        Thread.sleep(1_000);

        assertTrue(managerExists(userSubmittedId), "User-submitted manager (external_id IS NULL) must not be deleted");
        assertFalse(managerExists(seedId));
    }

    // ── Service-level trigger: createManager ─────────────────────────────────

    @Test
    void createManager_triggersDeletionOfOneSeedManager() throws Exception {
        long seed1 = insertSeedManager("seed_006", "Fake Alpha", "SeedCo", "Manager");
        long seed2 = insertSeedManager("seed_007", "Fake Beta",  "SeedCo", "Lead");
        String auth0Id = insertUser("auth0|create-mgr", "CreateMgrUser");

        await(service.createManager(auth0Id, validCreateManagerBody("Gordon Gray", "NewCo Ltd", "VP Engineering"), null));
        Thread.sleep(1_500);

        int remaining = (managerExists(seed1) ? 1 : 0) + (managerExists(seed2) ? 1 : 0);
        assertEquals(1, remaining, "Exactly one fake manager should be removed after createManager");
    }

    @Test
    void createManager_noSeedManagers_stillSucceeds() throws Exception {
        String auth0Id = insertUser("auth0|no-seeds", "NoSeedsUser");

        Row result = await(service.createManager(auth0Id,
            validCreateManagerBody("Holly Hart", "CleanCo", "Director"), null));
        assertNotNull(result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertSeedManager(String externalId, String name, String company, String title)
            throws Exception {
        return await(pool
            .preparedQuery("""
                INSERT INTO managers(name,company,title,image,status,approval_status,external_id,
                                     overall_rating,reviews_count,category_averages)
                VALUES ($1,$2,$3,'img','active','approved',$4,0,0,'{}') RETURNING id
                """)
            .execute(Tuple.of(name, company, title, externalId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool
            .preparedQuery("INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private void insertReviewDirect(long managerId, String auth0Id) throws Exception {
        UUID userId = await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
        // Columns: overall_rating + 10 categories = 11 numeric values
        await(pool.preparedQuery("""
                INSERT INTO reviews(manager_id, user_id, author, overall_rating,
                    communication_style, perceived_approachability, perceived_clarity_of_expectations,
                    feedback_style, perceived_supportiveness, decision_making_style,
                    organization_and_planning_style, delegation_style, perceived_professional_demeanor,
                    overall_working_experience, manager_company, manager_title,
                    worked_from, verified, helpful_count, created_at, updated_at)
                VALUES ($1,$2,'Tester',4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0,4.0,
                        'Corp','Title',$3,true,0,now(),now())
                """)
            .execute(Tuple.of(managerId, userId, LocalDate.of(2022, 1, 1))));
    }

    private void insertCareerHistoryDirect(long managerId) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO career_history(manager_id, company, title, start_date)
                VALUES ($1, 'Corp', 'Title', $2)
                """)
            .execute(Tuple.of(managerId, OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))));
    }

    private boolean managerExists(long managerId) throws Exception {
        return 1L == await(pool
            .preparedQuery("SELECT COUNT(*) FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
    }

    private long countRelated(String table, String column, long id) throws Exception {
        // table and column are always hardcoded by callers — no injection risk
        return await(pool
            .preparedQuery("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = $1")
            .execute(Tuple.of(id))
            .map(rs -> rs.iterator().next().getLong(0)));
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
