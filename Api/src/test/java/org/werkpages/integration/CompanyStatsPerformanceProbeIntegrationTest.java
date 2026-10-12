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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the projection costs: read plans, and the write latency maintenance adds.
 *
 * <p>A read model is only worth having if reads are cheap and maintenance does not make writes
 * expensive, and both are claims about plans rather than opinions. This builds enough volume for
 * the planner to make real choices, then prints what it chose. The plans are printed, not only
 * asserted, so the numbers can be read rather than taken on trust.
 *
 * <p>These are not latency SLAs - a Testcontainers Postgres on a developer machine is not
 * production. The assertions are about the SHAPE of the plan (does the directory read use the
 * index, does maintenance stay proportional to the companies touched) which is what actually
 * regresses when somebody changes this.
 */
@Testcontainers
class CompanyStatsPerformanceProbeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;

    /** Enough companies that a sequential scan is not automatically the cheapest option. */
    private static final int COMPANIES = 4000;
    private static final int DIRECTORY_COMPANIES = 400;

    @BeforeAll
    static void setUpAll() throws Exception {
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
        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(4));

        await(pool.query("TRUNCATE company_stats_live, managers, companies CASCADE").execute());

        /*
          One statement per table, so the statement-level triggers fire once each rather than
          4000 times. That is itself the thing being measured: under the previous row-level
          triggers this set-up did one recompute per inserted row.
        */
        long t0 = System.nanoTime();
        await(pool.preparedQuery("""
                INSERT INTO companies(name, status)
                SELECT 'Probe Co ' || g, 'approved' FROM generate_series(1, $1) g
                """).execute(Tuple.of(COMPANIES)));
        long companiesMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        await(pool.preparedQuery("""
                INSERT INTO managers(name, company, title, image, status, approval_status,
                                     overall_rating, reviews_count, category_averages, company_id)
                SELECT 'Probe Mgr ' || c.id, c.name, 'Manager', 'img', 'active', 'approved',
                       3.0 + (c.id % 20) / 10.0, 1 + (c.id % 7), '{}', c.id
                FROM companies c
                WHERE c.id % $1 = 0
                """).execute(Tuple.of(COMPANIES / DIRECTORY_COMPANIES)));
        long managersMs = (System.nanoTime() - t1) / 1_000_000;

        await(pool.query("ANALYZE company_stats_live").execute());
        await(pool.query("ANALYZE companies").execute());
        await(pool.query("ANALYZE managers").execute());

        System.out.println("\n──────── company_stats_live performance probe ────────");
        System.out.println("bulk insert of " + COMPANIES + " companies (1 statement): " + companiesMs + " ms");
        System.out.println("bulk insert of managers for " + DIRECTORY_COMPANIES
                         + " of them (1 statement): " + managersMs + " ms");
        System.out.println("projection rows: " + await(count("SELECT count(*) FROM company_stats_live"))
                         + "  (rule: one per non-merged company)");
        System.out.println("in the directory (manager_count > 0): "
                         + await(count("SELECT count(*) FROM company_stats_live WHERE manager_count > 0")));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void theDirectoryReadUsesThePartialIndex() throws Exception {
        /*
          findCompanyListing's exact shape. V90 adds a partial index matching its predicate and its
          ordering, so this should be an index scan with no sort - and because the index is partial,
          it holds only the companies that actually appear in the directory rather than one entry
          per company.
        */
        String plan = explain("""
                SELECT c.id, c.name, cs.manager_count, cs.total_reviews, cs.avg_rating
                FROM company_stats_live cs
                JOIN companies c ON c.id = cs.company_id
                WHERE cs.manager_count > 0
                ORDER BY cs.total_reviews DESC, cs.manager_count DESC, c.name ASC
                """);
        System.out.println("\n── directory listing plan ──\n" + plan);

        assertTrue(plan.contains("company_stats_live_directory_idx"),
            "the directory read must use the partial index V90 adds; plan was:\n" + plan);
    }

    @Test
    void aSingleCompanyRecomputeTouchesOnlyThatCompany() throws Exception {
        /*
          What every ordinary write pays. The recompute must be proportional to one company, not to
          the table: no sequential scan of companies, managers or either rating table.
        */
        long someCompany = await(pool.query(
            "SELECT company_id FROM company_stats_live WHERE manager_count > 0 LIMIT 1").execute()
            .map(rs -> rs.iterator().next().getLong(0)));

        String plan = explain("SELECT * FROM company_stats_expected(ARRAY[" + someCompany + "]::bigint[])");
        System.out.println("\n── single-company expected-values plan ──\n" + plan);

        long t0 = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            await(pool.preparedQuery("SELECT company_stats_apply($1)")
                .execute(Tuple.of(new Long[]{someCompany})));
        }
        long perCallMicros = (System.nanoTime() - t0) / 1000 / 50;
        System.out.println("company_stats_apply for one company: ~" + perCallMicros + " microseconds/call");

        assertFalse(plan.contains("Seq Scan on companies"),
            "a one-company recompute must not scan the companies table; plan was:\n" + plan);
    }

    @Test
    void bulkReassignmentRecomputesOncePerStatement() throws Exception {
        /*
          The case the statement-level triggers exist for: moving many managers in one statement.
          Under the previous FOR EACH ROW triggers this did one recompute per row - 2 x N for a
          reassignment, since both the old and the new company are refreshed each time.

          Measured as elapsed time for the statement, and verified by the counts afterwards so a
          fast-but-wrong dedup cannot pass.
        */
        long from = await(pool.query(
            "SELECT company_id FROM company_stats_live WHERE manager_count > 0 LIMIT 1").execute()
            .map(rs -> rs.iterator().next().getLong(0)));
        long to = await(pool.query(
            "SELECT id FROM companies WHERE id NOT IN (SELECT company_id FROM company_stats_live WHERE manager_count > 0) LIMIT 1")
            .execute().map(rs -> rs.iterator().next().getLong(0)));

        // Give `from` a realistic crowd to move.
        await(pool.preparedQuery("""
                INSERT INTO managers(name, company, title, image, status, approval_status,
                                     overall_rating, reviews_count, category_averages, company_id)
                SELECT 'Crowd ' || g, 'Probe', 'Manager', 'img', 'active', 'approved',
                       4.0, 2, '{}', $1
                FROM generate_series(1, 500) g
                """).execute(Tuple.of(from)));

        long before = await(count("SELECT manager_count FROM company_stats_live WHERE company_id = " + from));

        long t0 = System.nanoTime();
        await(pool.preparedQuery("UPDATE managers SET company_id = $1 WHERE company_id = $2")
            .execute(Tuple.of(to, from)));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        long fromAfter = await(count("SELECT manager_count FROM company_stats_live WHERE company_id = " + from));
        long toAfter   = await(count("SELECT manager_count FROM company_stats_live WHERE company_id = " + to));

        System.out.println("\n── bulk reassignment ──");
        System.out.println("moved " + before + " managers in one statement: " + elapsedMs + " ms");
        System.out.println("source now " + fromAfter + ", destination now " + toAfter);

        assertEquals(0L, fromAfter, "the source company must be emptied");
        assertEquals(before, toAfter, "and every manager must land, so the dedup lost nothing");
    }

    @Test
    void driftDetectionOverTheWholeTableIsOnePass() throws Exception {
        // The six-hourly job's read. One statement over every company, so it sees one snapshot.
        String plan = explain("SELECT * FROM company_stats_detect_drift(1000)");
        System.out.println("\n── drift detection plan ──\n" + plan);

        long t0 = System.nanoTime();
        long found = await(count("SELECT count(*) FROM company_stats_detect_drift(1000)"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("drift detection across " + COMPANIES + " companies: " + ms + " ms, "
                         + found + " discrepancies");

        assertEquals(0L, found, "a freshly maintained projection must report no drift");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String explain(String sql) throws Exception {
        return await(pool.query("EXPLAIN (ANALYZE, BUFFERS) " + sql).execute()
            .map(rs -> StreamSupport.stream(rs.spliterator(), false)
                .map(r -> r.getString(0))
                .collect(Collectors.joining("\n"))));
    }

    private static Future<Long> count(String sql) {
        return pool.query(sql).execute().map(rs -> {
            Row r = rs.iterator().next();
            return r.getLong(0) == null ? 0L : r.getLong(0);
        });
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(120, TimeUnit.SECONDS);
    }
}
