package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
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
import org.werkpages.service.ServiceException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class GhostManagerValidationIntegrationTest {

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
        ManagerRepository managerRepo = new ManagerRepository(pool);
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        EditRepository    editRepo    = new EditRepository(pool);
        ReportRepository  reportRepo  = new ReportRepository(pool);
        service = new ManagerService(managerRepo, reviewRepo, userRepo, editRepo, reportRepo, pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        // Truncate company_stats_live first to avoid deadlock: the background
        // updateCompanyStatsForManager task (fired by createGhostManager) holds a
        // RowExclusiveLock on company_stats_live while checking the companies FK.
        // A single CASCADE TRUNCATE on companies would deadlock with that task because
        // PostgreSQL tries to lock company_stats_live (via cascade) after already holding
        // the companies lock — a circular wait. Splitting into two statements breaks the cycle.
        await(pool.query("TRUNCATE company_stats_live").execute());
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void createGhostManager_withTestFirstName_returns400() {
        JsonObject body = ghostBody("Test Manager", "Acme Corp", "Engineer", "CA");
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> await(service.createGhostManager(body, null)));
        assertTrue(ex.getCause() instanceof ServiceException);
        assertEquals(400, ((ServiceException) ex.getCause()).getStatusCode());
        assertTrue(ex.getCause().getMessage().contains("real person"));
    }

    @Test
    void createGhostManager_withNumbersInName_returns400() {
        JsonObject body = ghostBody("John123 Doe", "Acme Corp", "Engineer", "CA");
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> await(service.createGhostManager(body, null)));
        assertTrue(ex.getCause() instanceof ServiceException);
        assertEquals(400, ((ServiceException) ex.getCause()).getStatusCode());
        assertTrue(ex.getCause().getMessage().contains("invalid characters"));
    }

    @Test
    void createGhostManager_withFakeFullName_returns400() {
        JsonObject body = ghostBody("Foo Bar", "Acme Corp", "Engineer", "CA");
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> await(service.createGhostManager(body, null)));
        assertTrue(ex.getCause() instanceof ServiceException);
        assertEquals(400, ((ServiceException) ex.getCause()).getStatusCode());
    }

    @Test
    void createGhostManager_withSingleWordName_returns400() {
        JsonObject body = ghostBody("Alice", "Acme Corp", "Engineer", "CA");
        ExecutionException ex = assertThrows(ExecutionException.class,
            () -> await(service.createGhostManager(body, null)));
        assertTrue(ex.getCause() instanceof ServiceException);
        assertEquals(400, ((ServiceException) ex.getCause()).getStatusCode());
    }

    @Test
    void createGhostManager_withValidName_succeeds() throws Exception {
        JsonObject body = ghostBody("Alice Smith", "Acme Corp", "Engineer", "CA");
        JsonObject result = await(service.createGhostManager(body, null));
        assertNotNull(result);
        assertEquals("Alice Smith", result.getString("name"));
        assertTrue(result.getBoolean("created"));
    }

    @Test
    void createGhostManager_withValidNameAlreadyExists_returnsExisting() throws Exception {
        JsonObject body = ghostBody("Alice Smith", "Acme Corp", "Engineer", "CA");
        JsonObject first  = await(service.createGhostManager(body, null));
        JsonObject second = await(service.createGhostManager(body, null));
        assertTrue(first.getBoolean("created"));
        assertFalse(second.getBoolean("created"));
        assertEquals(first.getLong("id"), second.getLong("id"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JsonObject ghostBody(String name, String company, String title, String country) {
        return new JsonObject()
            .put("name",    name)
            .put("company", company)
            .put("title",   title)
            .put("country", country);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
