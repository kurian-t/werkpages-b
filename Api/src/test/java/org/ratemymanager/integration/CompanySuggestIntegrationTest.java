package org.ratemymanager.integration;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
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
import org.ratemymanager.repository.EditRepository;
import org.ratemymanager.repository.ManagerRepository;
import org.ratemymanager.repository.ReportRepository;
import org.ratemymanager.repository.ReviewRepository;
import org.ratemymanager.repository.UserRepository;
import org.ratemymanager.service.ManagerService;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class CompanySuggestIntegrationTest {

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
        ReviewRepository  reviewRepo  = new ReviewRepository(pool);
        UserRepository    userRepo    = new UserRepository(pool);
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

    @Test
    void suggestCompanies_matchingQuery_returnsResults() throws Exception {
        insertManagerWithStatus("Alice A", "Acme Corp", "Manager", "approved");
        insertManagerWithStatus("Bob B",   "Acme Industries", "Director", "ghost");
        insertManagerWithStatus("Carol C", "Skynet Inc", "Engineer", "approved");

        JsonArray results = await(service.suggestCompanies("acme"));

        assertEquals(2, results.size());
        long acmeCount = StreamSupport.stream(results.spliterator(), false)
            .map(o -> ((io.vertx.core.json.JsonObject) o).getString("name"))
            .filter(n -> n.toLowerCase().contains("acme"))
            .count();
        assertEquals(2, acmeCount);
    }

    @Test
    void suggestCompanies_caseInsensitive_matchesRegardlessOfCase() throws Exception {
        insertManagerWithStatus("Dave D", "Globex Corporation", "VP", "approved");

        JsonArray results = await(service.suggestCompanies("GLOBEX"));

        assertEquals(1, results.size());
        assertEquals("Globex Corporation", results.getJsonObject(0).getString("name"));
    }

    @Test
    void suggestCompanies_pendingManagerExcluded() throws Exception {
        insertManagerWithStatus("Eve E", "Hidden Corp", "Manager", "pending_approval");

        JsonArray results = await(service.suggestCompanies("Hidden"));

        assertEquals(0, results.size());
    }

    @Test
    void suggestCompanies_emptyQuery_returnsEmpty() throws Exception {
        insertManagerWithStatus("Frank F", "Some Corp", "Manager", "approved");

        JsonArray results = await(service.suggestCompanies(""));

        assertEquals(0, results.size());
    }

    @Test
    void suggestCompanies_noMatch_returnsEmpty() throws Exception {
        insertManagerWithStatus("Grace G", "Umbrella Corp", "Manager", "approved");

        JsonArray results = await(service.suggestCompanies("xyz123"));

        assertEquals(0, results.size());
    }

    @Test
    void suggestCompanies_deduplicatesCompanyNames() throws Exception {
        insertManagerWithStatus("Hank H", "Initech", "Manager", "approved");
        insertManagerWithStatus("Iris I",  "Initech", "Director", "approved");

        JsonArray results = await(service.suggestCompanies("init"));

        assertEquals(1, results.size());
        assertEquals("Initech", results.getJsonObject(0).getString("name"));
    }

    private long insertManagerWithStatus(String name, String company, String title, String status) throws Exception {
        return await(pool
            .preparedQuery("INSERT INTO managers(name,company,title,image,status,approval_status,overall_rating,reviews_count,category_averages) " +
                           "VALUES ($1,$2,$3,'img','active',$4,0,0,'{}') RETURNING id")
            .execute(Tuple.of(name, company, title, status))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
