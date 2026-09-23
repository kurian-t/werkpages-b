package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.repository.ConfidenceRepository;
import org.werkpages.repository.ManagerRepository;
import org.werkpages.repository.ProofChallengeRepository;
import org.werkpages.repository.ReviewRepository;
import org.werkpages.service.MaintenanceSweep;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The daily housekeeping: that it does its work, and that it ever starts.
 *
 * <p>The second half is the one that mattered. This was scheduled with the one-argument
 * {@code setPeriodic(86_400_000L, ...)}, which fires for the first time a <b>full period</b> after
 * boot. Every deploy restarts the process and resets that timer, so on a service deployed more
 * than once a day the sweep never ran - for as long as it had existed. Soft-deleted reviews were
 * never restored, expired placeholders were never cleared, and nothing anywhere reported it,
 * because a timer that has not fired looks exactly like a timer with nothing to do.
 */
@Testcontainers
class MaintenanceSweepIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("sweep_test")
        .withUsername("test")
        .withPassword("test");

    static Pool             pool;
    static Vertx            vertx;
    static MaintenanceSweep sweep;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        vertx = Vertx.vertx();
        pool = PgPool.pool(vertx, new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()), new PoolOptions().setMaxSize(5));

        sweep = new MaintenanceSweep(
            new ReviewRepository(pool), new ManagerRepository(pool), new CompanyRepository(pool),
            new ProofChallengeRepository(pool), new ConfidenceRepository(pool));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null)  pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users CASCADE").execute());
    }

    /**
     * The first run happens shortly after scheduling, not a full period later.
     *
     * <p>This is the regression test for the outage. It schedules with a tiny initial delay and a
     * deliberately huge period: on the fixed three-argument form the sweep runs in milliseconds,
     * and on the one-argument form it would not run for an hour, so the latch times out and the
     * test fails. The gap between the two numbers is what makes the difference detectable.
     */
    @Test
    void schedule_runsShortlyAfterStart_notAFullPeriodLater() throws Exception {
        long managerId = insertManagerWithExpiredPlaceholder();
        CountDownLatch swept = new CountDownLatch(1);

        // Poll for the effect rather than instrument the sweep: what matters is that it ran.
        vertx.setPeriodic(50, id -> countPlaceholders(managerId).onSuccess(n -> {
            if (n == 0) swept.countDown();
        }));

        sweep.schedule(vertx, 50L, 3_600_000L);

        assertTrue(swept.await(15, TimeUnit.SECONDS),
            "the sweep must run shortly after being scheduled - scheduled with only a period, "
          + "its first run is a whole period away and it never happens on a service that redeploys");
    }

    /** The production schedule must not defer the first run by anything like a full period. */
    @Test
    void productionDelays_startTheFirstRunPromptly() {
        assertTrue(MaintenanceSweep.INITIAL_DELAY_MS < MaintenanceSweep.PERIOD_MS / 10,
            "the initial delay is a grace period for startup, not another full cycle");
        assertTrue(MaintenanceSweep.INITIAL_DELAY_MS > 0,
            "some grace, so the sweep does not compete with the rest of boot");
    }

    /** runOnce is callable directly - the property that made any of this testable. */
    @Test
    void runOnce_clearsAnExpiredPlaceholder() throws Exception {
        long managerId = insertManagerWithExpiredPlaceholder();
        assertEquals(1L, await(countPlaceholders(managerId)), "precondition");

        await(sweep.runOnce());

        assertEquals(0L, await(countPlaceholders(managerId)),
            "an expired placeholder is removed, not merely ignored");
    }

    /** A manager carrying one placeholder review whose 14-day countdown has already run out. */
    private long insertManagerWithExpiredPlaceholder() throws Exception {
        long companyId = await(pool.preparedQuery(
                "INSERT INTO companies(name,status,slug) VALUES ('SweepCo','ghost','sweepco') RETURNING id")
            .execute().map(rs -> rs.iterator().next().getLong("id")));
        long managerId = await(pool.preparedQuery(
                "INSERT INTO managers(name,company,company_id,title,image,status,approval_status,"
              + "overall_rating,reviews_count,category_averages) "
              + "VALUES ('Swept One','SweepCo',$1,'VP','img','active','approved',4.0,1,'{}') RETURNING id")
            .execute(Tuple.of(companyId)).map(rs -> rs.iterator().next().getLong("id")));
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id,author,overall_rating,manager_company,manager_title,"
              + "worked_from,weight,weight_expires_on,created_at) "
              + "VALUES ($1,'Placeholder',4.0,'SweepCo','VP',CURRENT_DATE - 30,TRUE,CURRENT_DATE - 1,now())")
            .execute(Tuple.of(managerId)).mapEmpty());
        return managerId;
    }

    private Future<Long> countPlaceholders(long managerId) {
        return pool.preparedQuery("SELECT COUNT(*) AS c FROM reviews WHERE manager_id = $1 AND weight = TRUE")
            .execute(Tuple.of(managerId))
            .map(rs -> rs.iterator().next().getLong("c"));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
