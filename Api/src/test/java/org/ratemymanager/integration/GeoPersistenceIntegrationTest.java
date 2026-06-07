package org.ratemymanager.integration;

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
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
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

/** Verifies that inferred state/city are persisted on the ghost / auto-create paths. */
@Testcontainers
class GeoPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("ratemymanager_test")
        .withUsername("test")
        .withPassword("test");

    static Pool              pool;
    static ManagerService    service;
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

        managerRepo = new ManagerRepository(pool);
        ReviewRepository reviewRepo = new ReviewRepository(pool);
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

    @Test
    void createGhostManager_persistsStateAndCity() throws Exception {
        JsonObject body = new JsonObject()
            .put("name", "Dana Geo")
            .put("company", "Geo Corp")
            .put("title", "Manager")
            .put("country", "Canada")
            .put("state", "Ontario")
            .put("city", "Toronto");

        JsonObject result = await(service.createGhostManager(body, null));
        assertTrue(result.getBoolean("created"));

        Row row = await(pool.preparedQuery("SELECT country, state, city FROM managers WHERE id = $1")
            .execute(Tuple.of(result.getLong("id"))).map(rs -> rs.iterator().next()));
        assertEquals("Canada",  row.getString("country"));
        assertEquals("Ontario", row.getString("state"));
        assertEquals("Toronto", row.getString("city"));
    }

    @Test
    void createGhostManager_withoutGeo_storesNullStateAndCity() throws Exception {
        JsonObject body = new JsonObject()
            .put("name", "Pat NoGeo")
            .put("company", "Plain Co")
            .put("title", "Manager")
            .put("country", "Canada");

        JsonObject result = await(service.createGhostManager(body, null));

        Row row = await(pool.preparedQuery("SELECT state, city FROM managers WHERE id = $1")
            .execute(Tuple.of(result.getLong("id"))).map(rs -> rs.iterator().next()));
        assertNull(row.getString("state"));
        assertNull(row.getString("city"));
    }

    @Test
    void createAutoApproved_persistsStateAndCity() throws Exception {
        UUID userId = await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, username, email) VALUES ($1,$2,$3) RETURNING id")
            .execute(Tuple.of("auth0|geo", "geouser", "geo@example.com"))
            .map(rs -> rs.iterator().next().getUUID("id")));

        Row row = await(managerRepo.createAutoApproved(
            "Sam Auto", "Auto Corp", "Director", "United States", "California", "San Francisco",
            userId, null));

        assertEquals("California",    row.getString("state"));
        assertEquals("San Francisco", row.getString("city"));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
