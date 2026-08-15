package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.werkpages.repository.ReportRepository;
import org.werkpages.repository.UserRepository;
import org.werkpages.service.ReportService;
import org.werkpages.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ReportServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool          pool;
    static ReportService service;

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
        UserRepository   userRepo   = new UserRepository(pool);
        ReportRepository reportRepo = new ReportRepository(pool);
        service = new ReportService(userRepo, reportRepo);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Input validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void nullReason_returns400() throws Exception {
        long managerId = insertManager("Test Manager");
        ServiceException ex = assertServiceException(
            service.reportManager(null, managerId, null, null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void blankReason_returns400() throws Exception {
        long managerId = insertManager("Test Manager");
        ServiceException ex = assertServiceException(
            service.reportManager(null, managerId, "   ", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void invalidReason_returns400() throws Exception {
        long managerId = insertManager("Test Manager");
        ServiceException ex = assertServiceException(
            service.reportManager(null, managerId, "made_up_reason", null));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    void commentTooLong_returns400() throws Exception {
        long managerId = insertManager("Test Manager");
        ServiceException ex = assertServiceException(
            service.reportManager(null, managerId, "other", "x".repeat(501)));
        assertEquals(400, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Manager existence
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void managerNotFound_returns404() {
        ServiceException ex = assertServiceException(
            service.reportManager(null, 999999L, "other", null));
        assertEquals(404, ex.getStatusCode());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Anonymous user
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void anonymousUser_success_createsReportWithNullUserId() throws Exception {
        long managerId = insertManager("Anon Report Manager");

        JsonObject result = await(service.reportManager(null, managerId, "other", null));
        assertTrue(result.getBoolean("success"));
        assertNotNull(result.getString("reportId"));
        assertNotNull(result.getString("createdAt"));

        long reportCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM reports WHERE manager_id = $1 AND user_id IS NULL")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, reportCount);
    }

    @Test
    void anonymousUser_withComment_persistsComment() throws Exception {
        long managerId = insertManager("Comment Manager");

        await(service.reportManager(null, managerId, "incorrect_information", "Wrong person listed"));

        String comment = await(pool
            .preparedQuery("SELECT comment FROM reports WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getString("comment")));
        assertEquals("Wrong person listed", comment);
    }

    @Test
    void anonymousUser_canReportSameManagerMultipleTimes() throws Exception {
        long managerId = insertManager("Multi-Report Manager");

        await(service.reportManager(null, managerId, "other", null));
        await(service.reportManager(null, managerId, "duplicate_profile", null));

        long count = await(pool
            .preparedQuery("SELECT COUNT(*) FROM reports WHERE manager_id = $1")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(2L, count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Authenticated user
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void authenticatedUser_success_createsReportWithUserId() throws Exception {
        long managerId = insertManager("Auth Report Manager");
        String auth0Id = insertUser("auth0|reporter01", "Reporter01");
        UUID   userId  = findUserId(auth0Id);

        JsonObject result = await(service.reportManager(auth0Id, managerId, "incorrect_person", "Comment here"));
        assertTrue(result.getBoolean("success"));
        assertNotNull(result.getString("reportId"));

        long reportCount = await(pool
            .preparedQuery("SELECT COUNT(*) FROM reports WHERE manager_id = $1 AND user_id = $2")
            .execute(Tuple.of(managerId, userId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, reportCount);
    }

    @Test
    void authenticatedUser_alreadyReported_returns409() throws Exception {
        long managerId = insertManager("Dupe Report Manager");
        String auth0Id = insertUser("auth0|reporter02", "Reporter02");

        await(service.reportManager(auth0Id, managerId, "other", null));

        ServiceException ex = assertServiceException(
            service.reportManager(auth0Id, managerId, "other", null));
        assertEquals(409, ex.getStatusCode());
        assertTrue(ex.getMessage().contains("already_reported"));
    }

    @Test
    void authenticatedUser_reportPersistedWithCorrectReason() throws Exception {
        long managerId = insertManager("Reason Check Manager");
        String auth0Id = insertUser("auth0|reporter03", "Reporter03");
        UUID   userId  = findUserId(auth0Id);

        await(service.reportManager(auth0Id, managerId, "never_worked_here", null));

        String reason = await(pool
            .preparedQuery("SELECT reason FROM reports WHERE manager_id = $1 AND user_id = $2")
            .execute(Tuple.of(managerId, userId))
            .map(rs -> rs.iterator().next().getString("reason")));
        assertEquals("never_worked_here", reason);
    }

    @Test
    void authenticatedUser_userNotInDb_createsAnonymousReport() throws Exception {
        long managerId = insertManager("Anon Fallback Manager");

        // auth0Id provided but not in DB — treated as anonymous
        JsonObject result = await(service.reportManager("auth0|not-in-db", managerId, "other", null));
        assertTrue(result.getBoolean("success"));

        long count = await(pool
            .preparedQuery("SELECT COUNT(*) FROM reports WHERE manager_id = $1 AND user_id IS NULL")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong(0)));
        assertEquals(1L, count);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // All valid reasons
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void allValidReasons_accepted() throws Exception {
        String[] reasons = {
            "incorrect_person", "never_worked_here", "duplicate_profile",
            "incorrect_information", "other"
        };
        for (String reason : reasons) {
            long managerId = insertManager("Manager for " + reason);
            JsonObject result = await(service.reportManager(null, managerId, reason, null));
            assertTrue(result.getBoolean("success"), "Reason should be valid: " + reason);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private long insertManager(String name) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
            "VALUES ($1,'Corp','Title','img','active','approved',0,0,'{}') RETURNING id")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private String insertUser(String auth0Id, String username) throws Exception {
        await(pool.preparedQuery(
            "INSERT INTO users(auth0_id, email, username, first_name, last_name) VALUES ($1,$2,$3,$4,$5)")
            .execute(Tuple.of(auth0Id, username + "@test.com", username, "Test", "User")));
        return auth0Id;
    }

    private UUID findUserId(String auth0Id) throws Exception {
        return await(pool
            .preparedQuery("SELECT id FROM users WHERE auth0_id = $1")
            .execute(Tuple.of(auth0Id))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private static ServiceException assertServiceException(Future<?> future) {
        try {
            future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            fail("Expected future to fail");
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ServiceException se) return se;
            fail("Expected ServiceException but got: " + e.getCause());
            return null;
        } catch (Exception e) {
            fail("Unexpected exception: " + e);
            return null;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
