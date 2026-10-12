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
import org.werkpages.repository.CompanyRepository;
import org.werkpages.service.CompanyStatsRebuilder;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mixed-version window: a pre-V90 backend still writing to a V90 database.
 *
 * <h2>Why this test exists</h2>
 *
 * The deployment order is Werkpages first, because V90 introduces the functions both backends now
 * call. That leaves a real window in which the NEW schema is live and the OLD RateMyManagers jar
 * is still running - and the old jar had a six-hourly job that executed its own copy of these
 * aggregates directly against this table.
 *
 * <p>I previously asserted that this was safe because "the triggers keep things correct
 * regardless". That is an explanation, not evidence, and it is the wrong shape of argument: if the
 * old statement writes after a trigger has written, the last writer wins and the trigger's work is
 * undone. Nothing about having triggers prevents that.
 *
 * <p>So this reproduces the exact sequence, with the old statement reproduced VERBATIM below rather
 * than described.
 *
 * <h2>What the old statement was</h2>
 *
 * RMM's {@code CompanyRepository.refreshCompanyStats()} before this change: an
 * {@code INSERT ... SELECT ... ON CONFLICT DO UPDATE} over an INNER JOIN on managers, writing five
 * of the nine columns. Its two relevant properties, and they pull in opposite directions:
 *
 * <ul>
 *   <li>The INNER JOIN means a company with no qualifying managers produces NO row from its SELECT,
 *       so {@code ON CONFLICT} never fires for it and its existing row is left untouched. That is
 *       what makes a workplace-only company survive an old-jar sweep - not the triggers.</li>
 *   <li>It updates only manager_count, total_reviews, avg_rating and logo_url, so the four columns
 *       V79 added cannot be clobbered by it at all.</li>
 * </ul>
 *
 * The question these tests answer is whether the five columns it DOES write still agree with V90's
 * calculation. If the two disagree anywhere, the old jar actively corrupts the projection for every
 * company that has managers, every six hours, for the length of the deployment window.
 */
@Testcontainers
class CompanyStatsMixedVersionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static CompanyStatsRebuilder maintenance;
    static CompanyRepository companyRepo;

    /**
     * RMM's pre-V90 refreshCompanyStats(), verbatim.
     *
     * <p>Copied from git history, not paraphrased. A paraphrase would test the paraphrase: if this
     * said LEFT JOIN, or listed the nine columns, it would prove the safety of a statement that
     * never shipped.
     */
    private static final String PRE_V90_RMM_WRITER = """
            INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating, logo_url, updated_at)
            SELECT c.id,
                   COUNT(DISTINCT m.id),
                   COALESCE(SUM(m.reviews_count), 0),
                   ROUND(AVG(m.overall_rating) FILTER (WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0)::NUMERIC, 1),
                   COALESCE(
                       MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url LIKE 'https://img.logo.dev/%'),
                       c.logo_url,
                       MIN(m.company_logo_url) FILTER (WHERE m.company_logo_url IS NOT NULL)
                   ),
                   now()
            FROM companies c
            JOIN managers m ON m.company_id = c.id
            WHERE m.approval_status IN ('approved', 'ghost')
              AND (m.external_id IS NULL OR m.external_id NOT LIKE 'seed_%')
            GROUP BY c.id, c.logo_url
            ON CONFLICT (company_id) DO UPDATE SET
                manager_count = EXCLUDED.manager_count,
                total_reviews = EXCLUDED.total_reviews,
                avg_rating    = EXCLUDED.avg_rating,
                logo_url      = EXCLUDED.logo_url,
                updated_at    = now()
            """;

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
        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(4));
        maintenance = new CompanyStatsRebuilder(pool);
        companyRepo = new CompanyRepository(pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE company_stats_drift_log").execute());
        await(pool.query("TRUNCATE company_reviews, interview_reviews, managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void theOldWriterRunningAfterATriggerDoesNotChangeASingleNumber() throws Exception {
        /*
          THE EXACT DEPLOYMENT SEQUENCE: triggers populate the projection correctly, then the old
          jar's sweep runs over the top of it.

          Asserted by capturing every column before and after and requiring them equal. Comparing
          only the columns the old statement writes would miss it clobbering one it should not.
        */
        long managerCo = insertCompany("Mixed Managers Co");
        insertManager("Alice A", managerCo, "approved", null, 5, 4.0);
        insertManager("Bob B",   managerCo, "approved", null, 7, 2.0);
        insertCompanyReview(managerCo, 5);
        insertInterview(managerCo, 3);

        Row before = stats(managerCo).orElseThrow();
        // Independently specified: 2 eligible managers, 5 + 7 reviews, mean of 4.0 and 2.0.
        assertEquals(2L,  before.getLong("manager_count"));
        assertEquals(12L, before.getLong("total_reviews"));
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(before.getBigDecimal("avg_rating")));

        await(pool.query(PRE_V90_RMM_WRITER).execute());

        Row after = stats(managerCo).orElseThrow();
        assertEquals(before.getLong("manager_count"),        after.getLong("manager_count"));
        assertEquals(before.getLong("total_reviews"),        after.getLong("total_reviews"));
        assertEquals(before.getBigDecimal("avg_rating"),     after.getBigDecimal("avg_rating"));
        assertEquals(before.getString("logo_url"),           after.getString("logo_url"));
        assertEquals(before.getLong("workplace_count"),      after.getLong("workplace_count"));
        assertEquals(before.getBigDecimal("workplace_avg_rating"), after.getBigDecimal("workplace_avg_rating"));
        assertEquals(before.getLong("interview_count"),      after.getLong("interview_count"));
        assertEquals(before.getBigDecimal("interview_avg_rating"), after.getBigDecimal("interview_avg_rating"));

        // And the shared checker, which computes from source independently of both, sees no drift.
        assertTrue(await(maintenance.reconcile()).isEmpty(),
            "the old writer must not introduce drift: " + await(maintenance.reconcile()));
    }

    @Test
    void theOldWriterCannotEraseAWorkplaceOnlyCompany() throws Exception {
        /*
          The case that would be a data-visible outage during the window, and the one my explanation
          waved at. A company rated as a workplace with no managers listed is exactly what the old
          code could not represent.

          It survives because the old statement's INNER JOIN produces no row for such a company, so
          its ON CONFLICT never fires. That is a property of the statement, now pinned by a test
          rather than asserted in prose.
        */
        long workplaceOnly = insertCompany("Mixed Workplace Only Ltd");
        insertCompanyReview(workplaceOnly, 4);
        insertCompanyReview(workplaceOnly, 2);
        insertInterview(workplaceOnly, 5);

        Row before = stats(workplaceOnly).orElseThrow();
        assertEquals(0L, before.getLong("manager_count"));
        assertEquals(2L, before.getLong("workplace_count"));
        assertEquals(1L, before.getLong("interview_count"));

        await(pool.query(PRE_V90_RMM_WRITER).execute());

        Row after = stats(workplaceOnly).orElseThrow(
            () -> new AssertionError("the old writer must not remove a workplace-only company's row"));
        assertEquals(2L, after.getLong("workplace_count"), "its ratings must still be counted");
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(after.getBigDecimal("workplace_avg_rating")));
        assertEquals(1L, after.getLong("interview_count"));
        assertEquals(0L, after.getLong("manager_count"));
    }

    @Test
    void aRatingSubmittedDuringTheWindowSurvivesTheOldWriter() throws Exception {
        /*
          Interleaved rather than sequential: somebody rates a company, the trigger updates the
          projection, and the old jar's sweep lands immediately afterwards. The rating must still be
          counted after the sweep.
        */
        long c = insertCompany("Window Co");
        insertManager("Alice A", c, "approved", null, 3, 4.0);

        await(pool.query(PRE_V90_RMM_WRITER).execute());        // old sweep, before the rating
        insertCompanyReview(c, 5);                              // live submission, trigger fires
        assertEquals(1L, stats(c).orElseThrow().getLong("workplace_count"));
        await(pool.query(PRE_V90_RMM_WRITER).execute());        // old sweep, after the rating

        Row s = stats(c).orElseThrow();
        assertEquals(1L, s.getLong("workplace_count"), "the new rating must survive the old sweep");
        assertEquals(0, new java.math.BigDecimal("5.0").compareTo(s.getBigDecimal("workplace_avg_rating")));
        assertEquals(1L, s.getLong("manager_count"));
        assertEquals(3L, s.getLong("total_reviews"));
        assertTrue(await(maintenance.reconcile()).isEmpty());
    }

    @Test
    void theNewEntryPointStillWorksForABackendThatCallsTheOldMethodName() throws Exception {
        /*
          Rollback safety, from the other direction: refreshCompanyStats() is kept as a method name
          and now delegates to company_stats_apply(NULL). Anything still calling it - either
          backend, or a test - must get a correct full recompute rather than an error.
        */
        long a = insertCompany("Rollback A");
        long b = insertCompany("Rollback B");
        insertManager("Alice A", a, "approved", null, 4, 4.0);
        insertCompanyReview(b, 3);

        await(pool.query("UPDATE company_stats_live SET manager_count = 55, workplace_count = 55").execute());
        await(companyRepo.refreshCompanyStats());

        assertEquals(1L, stats(a).orElseThrow().getLong("manager_count"));
        assertEquals(4L, stats(a).orElseThrow().getLong("total_reviews"));
        assertEquals(1L, stats(b).orElseThrow().getLong("workplace_count"));
        assertEquals(0L, stats(b).orElseThrow().getLong("manager_count"));
    }

    @Test
    void theV89FunctionNameStillWorks() throws Exception {
        // refresh_company_stats(id) is kept as a wrapper, so a jar rolled back to the V89-era code
        // path - whose triggers called it by that name - still maintains the projection.
        long c = insertCompany("Wrapper Co");
        insertManager("Alice A", c, "approved", null, 2, 4.0);
        await(pool.preparedQuery("UPDATE company_stats_live SET manager_count = 42 WHERE company_id = $1")
            .execute(Tuple.of(c)));

        await(pool.preparedQuery("SELECT refresh_company_stats($1)").execute(Tuple.of(c)));

        assertEquals(1L, stats(c).orElseThrow().getLong("manager_count"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private static void insertManager(String name, long companyId, String approvalStatus,
                                      String externalId, int reviewsCount, double rating) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO managers(name, company, title, image, status, approval_status,
                                     overall_rating, reviews_count, category_averages,
                                     company_id, external_id)
                VALUES ($1, 'Co', 'Manager', 'img', 'active', $2, $3, $4, '{}', $5, $6)
                """).execute(Tuple.of(name, approvalStatus, rating, reviewsCount, companyId, externalId)));
    }

    private static void insertCompanyReview(long companyId, int rating) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO company_reviews(company_id, overall_rating, work_life_balance,
                        compensation_benefits, career_growth, job_security,
                        workload_sustainability, senior_leadership, company_communication,
                        flexibility, inclusion_belonging, tools_resources, worked_from, author)
                VALUES ($1, $2, $2, $2, $2, $2, $2, $2, $2, $2, $2, $2, DATE '2022-01-01', 'TestAuthor')
                """).execute(Tuple.of(companyId, rating)));
    }

    private static void insertInterview(long companyId, int rating) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO interview_reviews(company_id, overall_rating, outcome, interview_year)
                VALUES ($1, $2, 'offer', 2025)
                """).execute(Tuple.of(companyId, rating)));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
