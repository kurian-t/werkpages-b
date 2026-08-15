package org.werkpages.integration;

import io.vertx.core.Future;
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

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ReviewSoftDeleteIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static ManagerService service;
    static UserRepository userRepo;
    static ReviewRepository reviewRepo;
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
        reviewRepo  = new ReviewRepository(pool);
        managerRepo = new ManagerRepository(pool);
        EditRepository   editRepo   = new EditRepository(pool);
        ReportRepository reportRepo = new ReportRepository(pool);
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

    @Test
    void delete_setsDeletedAtAndNullifiesUserId() throws Exception {
        long managerId = insertManager("Delete Target Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-1", "SoftDelUser1");
        UUID userId    = findUserId(auth0Id);

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = review.getUUID("id");

        await(reviewRepo.delete(reviewId, managerId));

        Row raw = await(pool
            .preparedQuery("SELECT deleted_at, user_id FROM reviews WHERE id = $1")
            .execute(Tuple.of(reviewId))
            .map(rs -> rs.iterator().next()));

        assertNotNull(raw.getOffsetDateTime("deleted_at"), "deleted_at must be set after soft delete");
        assertNull(raw.getUUID("user_id"), "user_id must be nullified immediately on soft delete");
    }

    @Test
    void delete_hidesReviewFromFindByManager() throws Exception {
        long managerId = insertManager("Hidden Review Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-2", "SoftDelUser2");

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = review.getUUID("id");

        long countBefore = await(reviewRepo.countByManager(managerId, null));
        assertEquals(1L, countBefore, "review must be visible before deletion");

        await(reviewRepo.delete(reviewId, managerId));

        long countAfter = await(reviewRepo.countByManager(managerId, null));
        assertEquals(0L, countAfter, "soft-deleted review must be excluded from count");

        long rowCount = await(reviewRepo.findByManager(managerId, 10, 0, "recent", null)
            .map(rs -> (long) rs.size()));
        assertEquals(0L, rowCount, "soft-deleted review must not appear in findByManager");
    }

    @Test
    void restoreExpiredDeletions_restoresOldSoftDeletes() throws Exception {
        long managerId = insertManager("Restore Expired Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-3", "SoftDelUser3");

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = review.getUUID("id");

        // Simulate deletion that happened 4 days ago (past the 3-day threshold)
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = $1, user_id = NULL WHERE id = $2")
            .execute(Tuple.of(OffsetDateTime.now().minusDays(4), reviewId)));

        int restored = await(reviewRepo.restoreExpiredDeletions());
        assertEquals(1, restored, "one expired deletion must be restored");

        long countAfter = await(reviewRepo.countByManager(managerId, null));
        assertEquals(1L, countAfter, "restored review must be publicly visible again");

        Row raw = await(pool
            .preparedQuery("SELECT deleted_at, user_id FROM reviews WHERE id = $1")
            .execute(Tuple.of(reviewId))
            .map(rs -> rs.iterator().next()));
        assertNull(raw.getOffsetDateTime("deleted_at"), "deleted_at must be cleared after restore");
        assertNull(raw.getUUID("user_id"), "user_id stays null — review is anonymous after restore");
    }

    @Test
    void restoreExpiredDeletions_doesNotRestoreRecentDeletes() throws Exception {
        long managerId = insertManager("Dont Restore Mgr", "Corp", "Title");
        String auth0Id = insertUser("auth0|softdel-4", "SoftDelUser4");

        Row review = await(service.createReview(auth0Id, managerId,
            validBody("Corp", "Title", "2022-01", null), null));
        UUID reviewId = review.getUUID("id");

        // Simulate deletion that happened 1 day ago (within the 3-day window)
        await(pool.preparedQuery("UPDATE reviews SET deleted_at = $1, user_id = NULL WHERE id = $2")
            .execute(Tuple.of(OffsetDateTime.now().minusDays(1), reviewId)));

        int restored = await(reviewRepo.restoreExpiredDeletions());
        assertEquals(0, restored, "recent soft-delete must not be restored yet");

        long count = await(reviewRepo.countByManager(managerId, null));
        assertEquals(0L, count, "review within 3-day window must remain hidden");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long insertManager(String name, String company, String title) throws Exception {
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,title,image,status,overall_rating,reviews_count,category_averages) " +
                           "VALUES ($1,$2,$3,'img','active',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool
            .preparedQuery("INSERT INTO users(auth0_id,email,username,first_name,last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static io.vertx.core.json.JsonObject validBody(
            String company, String title, String workedFrom, String workedUntil) {
        io.vertx.core.json.JsonObject ratings = new io.vertx.core.json.JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, 4.0);
        }
        io.vertx.core.json.JsonObject body = new io.vertx.core.json.JsonObject()
            .put("overallRating",  4.0)
            .put("ratings",        ratings)
            .put("managerCompany", company)
            .put("managerTitle",   title)
            .put("workedFrom",     workedFrom)
            .put("author",         "AnonTester")
            .put("authorType",     "anonymous");
        if (workedUntil != null) body.put("workedUntil", workedUntil);
        return body;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
