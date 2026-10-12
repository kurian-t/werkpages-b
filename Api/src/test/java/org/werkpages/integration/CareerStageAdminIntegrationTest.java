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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.service.AdminService;
import org.werkpages.service.ServiceException;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Removing a career stage from the trajectory.
 *
 * <h2>The production failure this comes from</h2>
 *
 * <p>A manager's profile showed a career stage at a company called "Ro". No such company existed.
 * Somebody had typed "ROCKY MOUNTAIN TURF CLUB" into the manager form, a placeholder review was
 * created from the half-typed fragment, and because the trajectory groups reviews by
 * {@code reviews.manager_company}, that fragment rendered as a complete career stage with its own
 * rating beside the real employer.
 *
 * <p>The admin had an Edit control on it and no Delete, because Delete acted on a
 * {@code career_history} row and the stage had none. Clearing it took hand-written SQL against the
 * production database.
 *
 * <h2>What the endpoint has to get right</h2>
 *
 * <ul>
 *   <li>a stage with no {@code career_history} row is still removable;
 *   <li>a stage holding genuine reviews is NOT silently destroyed, because an admin tidying a chart
 *       must not delete somebody's real opinion by accident;
 *   <li>those reviews can be moved to another role instead, keeping the rating;
 *   <li>the manager's cached rating and count are rebuilt either way. Nothing maintains them: there
 *       is no trigger on {@code reviews}, unlike the company projections.
 * </ul>
 */
@Testcontainers
class CareerStageAdminIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("career_stage_test").withUsername("test").withPassword("test");

    static Pool pool;
    static AdminService admin;

    private static final String ADMIN = "auth0|stage-admin";

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load().migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost()).setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername()).setPassword(postgres.getPassword()),
            new PoolOptions().setMaxSize(5));

        admin = new AdminService(new org.werkpages.repository.UserRepository(pool), new org.werkpages.repository.ManagerRepository(pool), new org.werkpages.repository.ReviewRepository(pool), new org.werkpages.repository.EditRepository(pool), new org.werkpages.repository.NotificationRepository(pool));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE reviews, career_history, company_stats_live, managers, companies, users CASCADE").execute());
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Stage','Admin','admin')")
            .execute(Tuple.of(ADMIN, "stageadmin@test.com", "stageadmin")));
    }

    // ── the regression ────────────────────────────────────────────────────────

    /**
     * A stage invented by a placeholder review can be removed, with no career_history row.
     *
     * <p>This is the "Ro" case exactly. Before the endpoint existed there was no way to do this
     * from the product at all.
     */
    @Test
    void aStageWithNoCareerHistoryRowCanBeRemoved() throws Exception {
        long managerId = insertManager("ROCKY MOUNTAIN TURF CLUB");
        insertSeedReview(managerId, "Ro", "Co-Founder", 4.1);
        insertRealReview(managerId, "ROCKY MOUNTAIN TURF CLUB", "CEO", 1.1);

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Ro")));

        assertTrue(result.getBoolean("success"));
        assertEquals(1, result.getInteger("reviewsAffected"));
        assertEquals(0L, countReviewsForCompany(managerId, "Ro"), "the invented stage is gone");
        assertEquals(1L, countReviewsForCompany(managerId, "ROCKY MOUNTAIN TURF CLUB"),
            "the real stage is untouched");
    }

    /** The cached rating and count are rebuilt, because no trigger does it. */
    @Test
    void theManagersCachedRatingIsRebuiltAfterRemoval() throws Exception {
        long managerId = insertManager("ROCKY MOUNTAIN TURF CLUB");
        insertSeedReview(managerId, "Ro", "Co-Founder", 4.1);
        insertRealReview(managerId, "ROCKY MOUNTAIN TURF CLUB", "CEO", 1.1);
        await(pool.preparedQuery("UPDATE managers SET reviews_count = 2, overall_rating = 2.6 WHERE id = $1")
            .execute(Tuple.of(managerId)));

        await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Ro")));

        Row m = managerRow(managerId);
        assertEquals(1, m.getInteger("reviews_count"),
            "a stale count would keep the header claiming the deleted rating");
        assertEquals(0, m.getBigDecimal("overall_rating").compareTo(new java.math.BigDecimal("1.1")));
    }

    // ── genuine reviews are protected ─────────────────────────────────────────

    /**
     * Deleting a stage that holds real reviews is refused unless it is confirmed.
     *
     * <p>The guard that stops a chart button becoming a way to destroy somebody's rating by
     * accident. The count travels in the message so the caller can state what is at stake.
     */
    @Test
    void deletingAStageWithRealReviewsIsRefusedWithoutConfirmation() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Wrong Co", "Director", 3.0);

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "delete").put("company", "Wrong Co"))));

        assertEquals(400, refused.getStatusCode());
        assertTrue(refused.getMessage().contains("1 real review"), refused.getMessage());
        assertEquals(1L, countReviewsForCompany(managerId, "Wrong Co"), "nothing may be deleted");
    }

    /** Confirmed, it goes. The admin has been told and said so. */
    @Test
    void aConfirmedDeleteRemovesRealReviewsToo() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Wrong Co", "Director", 3.0);

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Wrong Co")
                            .put("confirmRealReviews", true)));

        assertEquals(1, result.getInteger("reviewsAffected"));
        assertEquals(0L, countReviewsForCompany(managerId, "Wrong Co"));
    }

    // ── moving the ratings instead ────────────────────────────────────────────

    /**
     * Real reviews can be moved onto another role rather than destroyed.
     *
     * <p>The case the user asked for by name: the stage is wrong but the opinions are real. The
     * rating survives and counts under the role it belongs to.
     */
    @Test
    void realReviewsCanBeMovedToAnotherRole() throws Exception {
        long managerId = insertManager("ROCKY MOUNTAIN TURF CLUB");
        insertRealReview(managerId, "Ro", "Co-Founder", 4.0);
        insertRealReview(managerId, "ROCKY MOUNTAIN TURF CLUB", "CEO", 2.0);

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "reassign").put("company", "Ro")
                            .put("toCompany", "ROCKY MOUNTAIN TURF CLUB").put("toTitle", "CEO")));

        assertEquals(1, result.getInteger("reviewsAffected"));
        assertEquals(0L, countReviewsForCompany(managerId, "Ro"), "the bogus stage is gone");
        assertEquals(2L, countReviewsForCompany(managerId, "ROCKY MOUNTAIN TURF CLUB"),
            "and its rating now counts under the real role");
        assertEquals(2, managerRow(managerId).getInteger("reviews_count"),
            "nothing was destroyed, so the count is unchanged");
    }

    /** A reassign with no destination is refused rather than quietly blanking the company. */
    @Test
    void reassignWithoutATargetIsRefused() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Wrong Co", "Director", 3.0);

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "reassign").put("company", "Wrong Co"))));
        assertEquals(400, refused.getStatusCode());
        assertEquals(1L, countReviewsForCompany(managerId, "Wrong Co"));
    }

    /** Reassigning a stage onto itself is refused: it would be a no-op that reports success. */
    @Test
    void reassignOntoItselfIsRefused() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Acme", "Director", 3.0);

        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "reassign").put("company", "Acme")
                                .put("toCompany", "acme"))));
        assertEquals(400, refused.getStatusCode());
    }

    // ── the career_history row, when there is one ─────────────────────────────

    /** A stage that does have a row loses the row as well as its reviews. */
    @Test
    void aRecordedRoleLosesItsCareerHistoryRowToo() throws Exception {
        long managerId = insertManager("Acme");
        insertSeedReview(managerId, "Old Co", "Director", 4.0);
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, date_source) "
              + "VALUES ($1, 'Old Co', 'Director', now() - interval '3 years', 'unknown')")
            .execute(Tuple.of(managerId)));

        JsonObject result = await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "delete").put("company", "Old Co")));

        assertEquals(1, result.getInteger("entriesDeleted"));
        assertEquals(0L, await(pool.preparedQuery("SELECT count(*) AS c FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))).iterator().next().getLong("c"));
    }

    /**
     * After a reassign the empty row goes too.
     *
     * <p>Otherwise the stage redraws itself from the career_history row with no reviews under it,
     * which is the thing the admin just removed.
     */
    @Test
    void reassignAlsoRemovesTheNowEmptyCareerHistoryRow() throws Exception {
        long managerId = insertManager("Acme");
        insertRealReview(managerId, "Old Co", "Director", 4.0);
        insertRealReview(managerId, "Acme", "CEO", 3.0);
        await(pool.preparedQuery(
                "INSERT INTO career_history(manager_id, company, title, start_date, date_source) "
              + "VALUES ($1, 'Old Co', 'Director', now() - interval '3 years', 'unknown')")
            .execute(Tuple.of(managerId)));

        await(admin.adminResolveCareerStage(ADMIN, managerId,
            new JsonObject().put("action", "reassign").put("company", "Old Co")
                            .put("toCompany", "Acme").put("toTitle", "CEO")));

        assertEquals(0L, await(pool.preparedQuery("SELECT count(*) AS c FROM career_history WHERE manager_id = $1")
            .execute(Tuple.of(managerId))).iterator().next().getLong("c"));
    }

    // ── authorization ─────────────────────────────────────────────────────────

    /** Not an admin, not allowed. The whole thing is a moderation power. */
    @Test
    void aNonAdminCannotRemoveAStage() throws Exception {
        long managerId = insertManager("Acme");
        insertSeedReview(managerId, "Ro", "Director", 4.0);
        await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Not','Admin','user')")
            .execute(Tuple.of("auth0|not-admin", "notadmin@test.com", "notadmin")));

        assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage("auth0|not-admin", managerId,
                new JsonObject().put("action", "delete").put("company", "Ro"))));
        assertEquals(1L, countReviewsForCompany(managerId, "Ro"), "nothing may be removed");
    }

    /** An unknown action is refused rather than guessed at. */
    @Test
    void anUnknownActionIsRefused() throws Exception {
        long managerId = insertManager("Acme");
        ServiceException refused = assertThrows(ServiceException.class,
            () -> awaitFailure(admin.adminResolveCareerStage(ADMIN, managerId,
                new JsonObject().put("action", "obliterate").put("company", "Acme"))));
        assertEquals(400, refused.getStatusCode());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long insertManager(String company) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers(name, company, title, status, approval_status, reviews_count) "
              + "VALUES ('Max Gibb', $1, 'CEO', 'active', 'approved', 0) RETURNING id")
            .execute(Tuple.of(company))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** A placeholder: no author, weight true. Exactly what createSeedReview writes. */
    private void insertSeedReview(long managerId, String company, String title, double rating)
            throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, manager_company, "
              + "manager_title, worked_from, verified, helpful_count, weight) "
              + "VALUES ($1, NULL, $2, $3, $4, $5, now() - interval '2 years', true, 0, true)")
            .execute(Tuple.of(managerId, "LoyalFalcon" + (int) (rating * 10), rating, company, title)));
    }

    /** A real one: a genuine author, weight false. */
    private void insertRealReview(long managerId, String company, String title, double rating)
            throws Exception {
        UUID userId = await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Real','Person','user') RETURNING id")
            .execute(Tuple.of("auth0|rev-" + UUID.randomUUID(),
                              UUID.randomUUID() + "@test.com", "rev" + Math.abs(UUID.randomUUID().hashCode())))
            .map(rs -> rs.iterator().next().getUUID("id")));
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, manager_company, "
              + "manager_title, worked_from, verified, helpful_count, weight) "
              + "VALUES ($1, $2, $3, $4, $5, $6, now() - interval '2 years', true, 0, false)")
            .execute(Tuple.of(managerId, userId, "RealPerson" + (int) (rating * 10), rating, company, title)));
    }

    private long countReviewsForCompany(long managerId, String company) throws Exception {
        return await(pool.preparedQuery(
                "SELECT count(*) AS c FROM reviews WHERE manager_id = $1 "
              + "AND LOWER(TRIM(COALESCE(manager_company,''))) = LOWER(TRIM($2))")
            .execute(Tuple.of(managerId, company))).iterator().next().getLong("c");
    }

    private Row managerRow(long managerId) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId))).iterator().next();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static void awaitFailure(Future<?> future) throws Throwable {
        try {
            future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }
}
