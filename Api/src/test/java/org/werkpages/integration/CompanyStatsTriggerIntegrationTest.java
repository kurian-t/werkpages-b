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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the company_stats_live cache maintained by V47's trigger.
 *
 * <p>The bug this replaces: the application refresh is an INSERT ... SELECT ... ON CONFLICT
 * whose SELECT joins `managers`. With no qualifying managers the SELECT returns no rows, so
 * nothing is written and a previously cached row survives — a company with zero managers kept
 * advertising stale manager and review counts, and stayed visible in industry listings because
 * they filter on `manager_count > 0`. In production one company showed "1 manager, 1 review,
 * 4.1" against an industry header that correctly reported 0 reviews.
 *
 * <p>These tests drive the tables directly rather than going through a service, because the
 * guarantee under test belongs to the database: it must hold no matter which of the two
 * applications sharing this schema performed the write.
 */
@Testcontainers
class CompanyStatsTriggerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;

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
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // The orphan case — what the application refresh could not express
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void deletingLastManager_removesCachedStatsRow() throws Exception {
        long companyId = insertCompany("Redwater Partnership");
        long managerId = insertManager("Solo Manager", companyId, "approved", null, 1, 4.1);

        assertTrue(stats(companyId).isPresent(), "a qualifying manager must produce a cached row");

        await(pool.preparedQuery("DELETE FROM managers WHERE id = $1").execute(Tuple.of(managerId)));

        assertTrue(stats(companyId).isEmpty(),
            "with no managers left the cached row must be gone, not left advertising stale counts");
    }

    @Test
    void demotingLastManagerOutOfPublicStatuses_removesCachedStatsRow() throws Exception {
        long companyId = insertCompany("Rejected Co");
        long managerId = insertManager("Pending Pat", companyId, "approved", null, 2, 3.0);
        assertTrue(stats(companyId).isPresent());

        await(pool.preparedQuery("UPDATE managers SET approval_status = 'rejected' WHERE id = $1")
            .execute(Tuple.of(managerId)));

        assertTrue(stats(companyId).isEmpty(),
            "a manager leaving approved/ghost must not leave the company cached as populated");
    }

    @Test
    void seedOnlyCompany_hasNoCachedStatsRow() throws Exception {
        long companyId = insertCompany("Seeded Only Co");
        insertManager("Seed Sam", companyId, "approved", "seed_123", 5, 4.9);

        assertTrue(stats(companyId).isEmpty(),
            "seed managers are excluded from these aggregates, so no row should exist");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Ordinary maintenance
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void insertingManager_populatesCachedStats() throws Exception {
        long companyId = insertCompany("Acme Corp");
        insertManager("Alice A", companyId, "approved", null, 3, 4.0);
        insertManager("Bob B",   companyId, "ghost",    null, 1, 2.0);

        Row row = stats(companyId).orElseThrow();
        assertEquals(2L, row.getLong("manager_count"));
        assertEquals(4L, row.getLong("total_reviews"), "review counts sum across managers");
    }

    @Test
    void updatingReviewsCount_refreshesCachedStats() throws Exception {
        long companyId = insertCompany("Growing Co");
        long managerId = insertManager("Carla C", companyId, "approved", null, 1, 5.0);
        assertEquals(1L, stats(companyId).orElseThrow().getLong("total_reviews"));

        await(pool.preparedQuery("UPDATE managers SET reviews_count = 7 WHERE id = $1")
            .execute(Tuple.of(managerId)));

        assertEquals(7L, stats(companyId).orElseThrow().getLong("total_reviews"),
            "the cache must follow managers.reviews_count without an application call");
    }

    @Test
    void movingManagerBetweenCompanies_refreshesBothSides() throws Exception {
        long fromId = insertCompany("Old Employer");
        long toId   = insertCompany("New Employer");
        long managerId = insertManager("Mobile Mo", fromId, "approved", null, 2, 4.5);

        assertEquals(1L, stats(fromId).orElseThrow().getLong("manager_count"));

        await(pool.preparedQuery("UPDATE managers SET company_id = $1 WHERE id = $2")
            .execute(Tuple.of(toId, managerId)));

        assertTrue(stats(fromId).isEmpty(), "the company left behind must be cleaned up");
        assertEquals(1L, stats(toId).orElseThrow().getLong("manager_count"),
            "the destination company must pick the manager up");
    }

    @Test
    void deletingOneOfTwoManagers_leavesRowWithDecrementedCounts() throws Exception {
        long companyId = insertCompany("Duo Corp");
        long firstId = insertManager("First F",  companyId, "approved", null, 4, 4.0);
        insertManager("Second S", companyId, "approved", null, 6, 3.0);
        assertEquals(10L, stats(companyId).orElseThrow().getLong("total_reviews"));

        await(pool.preparedQuery("DELETE FROM managers WHERE id = $1").execute(Tuple.of(firstId)));

        Row row = stats(companyId).orElseThrow();
        assertEquals(1L, row.getLong("manager_count"));
        assertEquals(6L, row.getLong("total_reviews"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static Optional<Row> stats(long companyId) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM company_stats_live WHERE company_id = $1")
            .execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty()));
    }

    private static long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
            "INSERT INTO companies(name, status) VALUES ($1, 'approved') RETURNING id")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long insertManager(String name, long companyId, String approvalStatus,
                                      String externalId, int reviewsCount, double rating) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name, company, title, image, status, approval_status,
                                     overall_rating, reviews_count, category_averages,
                                     company_id, external_id)
                VALUES ($1, 'Co', 'Manager', 'img', 'active', $2, $3, $4, '{}', $5, $6)
                RETURNING id
                """)
            .execute(Tuple.of(name, approvalStatus, rating, reviewsCount, companyId, externalId))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
