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

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that overall_rating and reviews_count on the managers table are
 * recalculated correctly as reviews are submitted and deleted.
 *
 * This is a regression guard for the bug where ratings became stale and
 * managers showed incorrect ratings on the public directory and profiles.
 */
@Testcontainers
class RatingRecalculationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool            pool;
    static ManagerService  service;
    static ReviewRepository reviewRepo;
    static ManagerRepository managerRepo;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        PgConnectOptions opts = new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword());

        pool        = PgPool.pool(opts, new PoolOptions().setMaxSize(5));
        managerRepo = new ManagerRepository(pool);
        reviewRepo  = new ReviewRepository(pool);

        UserRepository   userRepo   = new UserRepository(pool);
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

    // ── first review sets the rating ──────────────────────────────────────────

    @Test
    void firstReview_setsOverallRatingAndCount() throws Exception {
        long managerId = insertManager("Alice Wong", "Acme", "Director");
        String auth0Id = insertUser("auth0|alice-1", "AliceTest1");

        await(service.createReview(auth0Id, managerId, reviewBody(4.0), null));
        // Background recalculate is fire-and-forget; call it synchronously to assert
        await(managerRepo.recalculate(managerId));

        Row m = fetchManager(managerId);
        assertEquals(1, m.getInteger("reviews_count"));
        assertEquals(0, new BigDecimal("4.0").compareTo(m.getBigDecimal("overall_rating")));
    }

    @Test
    void firstReview_withSpecificRating_exactlyThatRating() throws Exception {
        long managerId = insertManager("Bob Chen", "Corp", "Manager");
        String auth0Id = insertUser("auth0|bob-1", "BobTest111");

        await(service.createReview(auth0Id, managerId, reviewBody(3.2), null));
        await(managerRepo.recalculate(managerId));

        Row m = fetchManager(managerId);
        assertEquals(0, new BigDecimal("3.2").compareTo(m.getBigDecimal("overall_rating")));
    }

    // ── second review averages correctly ──────────────────────────────────────

    @Test
    void twoReviews_overallRatingIsAverage() throws Exception {
        long managerId = insertManager("Carol Lee", "Corp", "VP");
        String user1   = insertUser("auth0|carol-1", "CarolUsr1");
        String user2   = insertUser("auth0|carol-2", "CarolUsr2");

        await(service.createReview(user1, managerId, reviewBody(4.0, "U1"), null));
        await(service.createReview(user2, managerId, reviewBody(2.0, "U2"), null));
        await(managerRepo.recalculate(managerId));

        Row m = fetchManager(managerId);
        assertEquals(2, m.getInteger("reviews_count"));
        // (4.0 + 2.0) / 2 = 3.0
        assertEquals(0, new BigDecimal("3.0").compareTo(m.getBigDecimal("overall_rating")));
    }

    @Test
    void threeReviews_roundedToOneDecimal() throws Exception {
        long managerId = insertManager("Dave Park", "Corp", "Lead");
        String u1 = insertUser("auth0|dave-1", "DavUsr1111");
        String u2 = insertUser("auth0|dave-2", "DavUsr2222");
        String u3 = insertUser("auth0|dave-3", "DavUsr3333");

        await(service.createReview(u1, managerId, reviewBody(5.0, "U1"), null));
        await(service.createReview(u2, managerId, reviewBody(4.0, "U2"), null));
        await(service.createReview(u3, managerId, reviewBody(3.0, "U3"), null));
        await(managerRepo.recalculate(managerId));

        Row m = fetchManager(managerId);
        assertEquals(3, m.getInteger("reviews_count"));
        // (5 + 4 + 3) / 3 = 4.0 exactly
        assertEquals(0, new BigDecimal("4.0").compareTo(m.getBigDecimal("overall_rating")));
    }

    // ── soft delete decrements rating ─────────────────────────────────────────

    @Test
    void deleteReview_ratingRecalculatesWithoutDeletedReview() throws Exception {
        long managerId = insertManager("Eve Torres", "Corp", "CTO");
        String u1 = insertUser("auth0|eve-1", "EveUser1111");
        String u2 = insertUser("auth0|eve-2", "EveUser2222");

        await(service.createReview(u1, managerId, reviewBody(5.0, "U1"), null));
        Row review2 = await(service.createReview(u2, managerId, reviewBody(1.0, "U2"), null));
        await(managerRepo.recalculate(managerId));

        Row before = fetchManager(managerId);
        assertEquals(2, before.getInteger("reviews_count"));
        // (5 + 1) / 2 = 3.0
        assertEquals(0, new BigDecimal("3.0").compareTo(before.getBigDecimal("overall_rating")));

        // Delete the low review
        UUID reviewId = review2.getUUID("id");
        UUID userId2  = findUserId("auth0|eve-2");
        await(reviewRepo.delete(reviewId, managerId));
        await(managerRepo.recalculate(managerId));

        Row after = fetchManager(managerId);
        assertEquals(1, after.getInteger("reviews_count"));
        // Only review1 (5.0) remains
        assertEquals(0, new BigDecimal("5.0").compareTo(after.getBigDecimal("overall_rating")));
    }

    @Test
    void deleteLastReview_ratingBecomesNull() throws Exception {
        long managerId = insertManager("Frank Kim", "Corp", "Manager");
        String auth0Id = insertUser("auth0|frank-1", "FrankUsr11");

        Row review = await(service.createReview(auth0Id, managerId, reviewBody(4.5), null));
        await(managerRepo.recalculate(managerId));

        UUID reviewId = review.getUUID("id");
        await(reviewRepo.delete(reviewId, managerId));
        await(managerRepo.recalculate(managerId));

        Row m = fetchManager(managerId);
        assertEquals(0, m.getInteger("reviews_count"));
        assertNull(m.getBigDecimal("overall_rating"),
            "overall_rating must be null when no reviews remain");
    }

    // ── category averages recalculate ─────────────────────────────────────────

    @Test
    void categoryAverages_calculatedPerCategory() throws Exception {
        long managerId = insertManager("Grace Liu", "Corp", "Director");
        String u1 = insertUser("auth0|grace-1", "GraceUsr11");
        String u2 = insertUser("auth0|grace-2", "GraceUsr22");

        await(service.createReview(u1, managerId, reviewBodyWithCategory("U1", 4.0, "Communication Style", 5.0), null));
        await(service.createReview(u2, managerId, reviewBodyWithCategory("U2", 2.0, "Communication Style", 3.0), null));
        await(managerRepo.recalculate(managerId));

        Row m = fetchManager(managerId);
        io.vertx.core.json.JsonObject cats = m.getJsonObject("category_averages");
        assertNotNull(cats, "category_averages must be set");
        // Communication Style avg = (5 + 3) / 2 = 4.0
        assertEquals(4.0, cats.getDouble("Communication Style"), 0.01);
    }

    @Test
    void categoryAverages_nullWhenNoReviews() throws Exception {
        long managerId = insertManager("Henry Park", "Corp", "VP");
        String auth0Id = insertUser("auth0|henry-1", "HenryUsr11");

        Row review = await(service.createReview(auth0Id, managerId, reviewBody(3.0), null));
        await(managerRepo.recalculate(managerId));

        // Delete the only review
        await(reviewRepo.delete(review.getUUID("id"), managerId));
        await(managerRepo.recalculate(managerId));

        Row m = fetchManager(managerId);
        assertNull(m.getValue("category_averages"),
            "category_averages must be null when no reviews remain");
    }

    // ── multiple managers are independent ────────────────────────────────────

    @Test
    void multipleManagers_ratingsAreIndependent() throws Exception {
        long m1Id = insertManager("Manager One", "Corp1", "VP");
        long m2Id = insertManager("Manager Two", "Corp2", "Director");
        String u1 = insertUser("auth0|multi-1", "MultiUsr11");
        String u2 = insertUser("auth0|multi-2", "MultiUsr22");

        await(service.createReview(u1, m1Id, reviewBody(5.0), null));
        await(service.createReview(u2, m2Id, reviewBody(2.0), null));
        await(managerRepo.recalculate(m1Id));
        await(managerRepo.recalculate(m2Id));

        Row m1 = fetchManager(m1Id);
        Row m2 = fetchManager(m2Id);

        assertEquals(0, new BigDecimal("5.0").compareTo(m1.getBigDecimal("overall_rating")));
        assertEquals(0, new BigDecimal("2.0").compareTo(m2.getBigDecimal("overall_rating")));
        assertEquals(1, m1.getInteger("reviews_count"));
        assertEquals(1, m2.getInteger("reviews_count"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long insertManager(String name, String company, String title) throws Exception {
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
                           "VALUES ($1,$2,$3,'img','active','approved',null,0,null) RETURNING id")
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

    private Row fetchManager(long managerId) throws Exception {
        return await(pool
            .preparedQuery("SELECT overall_rating, reviews_count, category_averages FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next()));
    }

    // uniqueTag ensures the (author, manager_id, title, company) unique index doesn't conflict
    // when two different users review the same manager in the same test.
    private static JsonObject reviewBody(double overallRating, String uniqueTag) {
        JsonObject ratings = new JsonObject();
        for (String key : new String[]{
                "Communication Style", "Perceived Approachability",
                "Perceived Clarity of Expectations", "Feedback Style",
                "Perceived Supportiveness", "Decision Making Style",
                "Organization and Planning Style", "Delegation Style",
                "Perceived Professional Demeanor", "Overall Working Experience"}) {
            ratings.put(key, overallRating);
        }
        return new JsonObject()
            .put("overallRating",  overallRating)
            .put("ratings",        ratings)
            .put("managerCompany", "Test Corp")
            .put("managerTitle",   "Test Manager")
            .put("workedFrom",     "2022-01")
            .put("author",         "Anon" + uniqueTag)
            .put("authorType",     "anonymous");
    }

    private static JsonObject reviewBody(double overallRating) {
        return reviewBody(overallRating, "Solo");
    }

    private static JsonObject reviewBodyWithCategory(String tag, double overallRating, String categoryKey, double categoryValue) {
        JsonObject body = reviewBody(overallRating, tag);
        body.getJsonObject("ratings").put(categoryKey, categoryValue);
        return body;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
