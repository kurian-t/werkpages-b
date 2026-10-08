package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
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

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code company_stats_live}: the rebuild and the drift check that make it safe to read from.
 *
 * <p>CLAUDE.md section 21 allows a denormalised read table only alongside a rebuild that can
 * recreate it from source alone and a reconciliation that proves it has not drifted. This class
 * existed with neither tests nor any caller, which is barely better than not having it: nothing
 * had ever executed it, and its own documentation described a failure mode the database does not
 * actually have.
 *
 * <p>The projection carries three datasets, and since V89 all of them are maintained in the same
 * place: the database. Four triggers feed {@code refresh_company_stats}, which writes all nine
 * columns - {@code managers} (V47), and {@code companies.logo_url}, {@code company_reviews} and
 * {@code interview_reviews} (V89). The Java writers in both backends are gone.
 *
 * <p>Before V89 the workplace and interview columns had no trigger and were maintained only by
 * {@code CompanyReviewService} and {@code InterviewService}, which is why several tests here still
 * establish a baseline with {@link CompanyStatsRebuilder#rebuild()}: it is harmless now that the
 * triggers keep those columns correct, and it keeps each test independent of how many writers
 * happen to exist.
 *
 * <p>Deliberately parallel to the RateMyManagers test of the same name. That projection carries
 * the managers dataset only, so the two differ in which datasets they assert, not in how drift is
 * found.
 */
@Testcontainers
class CompanyStatsRebuilderIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool                  pool;
    static CompanyRepository     companyRepo;
    static CompanyStatsRebuilder rebuilder;

    @BeforeAll
    static void setUpAll() {
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()), new PoolOptions().setMaxSize(5));

        companyRepo = new CompanyRepository(pool);
        rebuilder   = new CompanyStatsRebuilder(pool, companyRepo);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("""
            TRUNCATE managers, companies, company_reviews, interview_reviews,
                     company_stats_live CASCADE
            """).execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── The rebuild ───────────────────────────────────────────────────────────

    @Test
    void rebuildRecreatesEveryDatasetFromSourceAlone() throws Exception {
        long acme = insertCompany("Acme");
        insertManager("Ada Approved", acme, "approved", 3);
        insertManager("Gus Ghost",    acme, "ghost",    2);
        insertCompanyReview(acme, 4.0);
        insertCompanyReview(acme, 2.0);
        insertInterviewReview(acme, 5.0);

        // Throw the projection away and recompute from source. Emptying it first is the only way
        // to test that: the V47 trigger has already written the manager columns on insert, so a
        // rebuild over a correct table proves nothing about reconstructing one.
        await(pool.query("TRUNCATE company_stats_live").execute());
        assertEquals(0, projected(acme, "manager_count"), "precondition: the projection is empty");

        await(rebuilder.rebuild());

        assertEquals(2, projected(acme, "manager_count"),   "approved and ghost both count");
        assertEquals(5, projected(acme, "total_reviews"),   "the sum of managers.reviews_count");
        assertEquals(2, projected(acme, "workplace_count"), "both workplace ratings");
        assertEquals(1, projected(acme, "interview_count"), "the interview experience");
        assertTrue(await(rebuilder.reconcile()).isEmpty(), "a fresh rebuild must not drift");
    }

    @Test
    void rebuildIgnoresPendingRejectedSeedAndDeletedRows() throws Exception {
        long acme = insertCompany("Acme");
        insertManager("Ada Approved", acme, "approved", 4);
        insertManager("Pat Pending",  acme, "pending_approval", 9);
        insertManager("Rex Rejected", acme, "rejected", 9);
        insertSeedManager("Sam Seeded", acme, 9);
        insertCompanyReview(acme, 4.0);
        insertDeletedCompanyReview(acme, 1.0);
        insertInterviewReview(acme, 5.0);
        insertDeletedInterviewReview(acme, 1.0);

        await(pool.query("TRUNCATE company_stats_live").execute());
        await(rebuilder.rebuild());

        // The projection feeds public surfaces, so it holds exactly what section 16 calls live.
        // Withdrawn ratings and synthetic seed placeholders are not opinions anyone gave.
        assertEquals(1, projected(acme, "manager_count"));
        assertEquals(4, projected(acme, "total_reviews"));
        assertEquals(1, projected(acme, "workplace_count"), "the withdrawn rating is excluded");
        assertEquals(1, projected(acme, "interview_count"), "the withdrawn experience is excluded");
        assertTrue(await(rebuilder.reconcile()).isEmpty());
    }

    /*
      The orphan case, and who actually handles it.

      The V47 trigger fires on the approval_status update and refresh_company_stats ends with a
      DELETE for exactly this case, so the row is gone before any application code looks at it.
      This class's own documentation used to claim the opposite. Pinned here so that if the
      trigger is ever dropped from the shared schema, or its explicit column list falls behind,
      this test says so rather than a managerless company quietly appearing in the public listing.
    */
    @Test
    void theDatabaseTriggerClearsAnOrphanRow() throws Exception {
        long acme = insertCompany("Acme");
        long adaId = insertManager("Ada Approved", acme, "approved", 3);
        assertEquals(1, projected(acme, "manager_count"), "the trigger populates on insert");

        await(pool.preparedQuery("UPDATE managers SET approval_status = 'rejected' WHERE id = $1")
            .execute(Tuple.of(adaId)));

        assertEquals(0, projected(acme, "manager_count"), "the trigger removed the orphan row");
        assertTrue(await(rebuilder.reconcile()).isEmpty(), "and nothing has drifted");
    }

    // ── The drift check: the three kinds of disagreement ──────────────────────
    //
    // Each asserts the data is UNCHANGED afterwards. A drift report that quietly repaired what it
    // found would destroy the only evidence that a writer is broken.

    /*
      Kind 1: a mismatched projected value, on each of the three datasets independently.

      Three writers means a bug can live in any one of them while the other two stay correct, so
      a check that only looked at the managers columns would miss two thirds of the surface.
    */
    @Test
    void reconcileDetectsMismatchedProjectedValues() throws Exception {
        long acme = insertCompany("Acme");
        insertManager("Ada Approved", acme, "approved", 3);
        insertCompanyReview(acme, 4.0);
        insertInterviewReview(acme, 5.0);

        /*
          Establish a correct baseline first.

          These inserts go straight to SQL, and company_stats_live's workplace_count and
          interview_count have NO database trigger - only the managers columns do. Those two are
          maintained solely by CompanyReviewService and InterviewService, so a direct insert
          leaves them at 0 and reconcile would be reporting that rather than the corruption this
          test is about. A rebuild puts every dataset in line with source, which is the state a
          healthy system is in.
        */
        await(rebuilder.rebuild());
        assertTrue(await(rebuilder.reconcile()).isEmpty(), "baseline must be clean");

        // Corrupt all four counts behind the writers' backs, as a bug in one of them would.
        await(pool.query("""
            UPDATE company_stats_live
               SET manager_count   = manager_count   + 7,
                   total_reviews   = total_reviews   + 11,
                   workplace_count = workplace_count + 5,
                   interview_count = interview_count + 2
            """).execute());

        List<CompanyStatsRebuilder.Drift> drift = await(rebuilder.reconcile());
        assertEquals(4, drift.size(), () -> "every dataset disagrees, so all four report: " + drift);
        assertEquals(8,  datasetDrift(drift, "managers").projected());
        assertEquals(1,  datasetDrift(drift, "managers").actual());
        assertEquals(14, datasetDrift(drift, "reviews").projected());
        assertEquals(3,  datasetDrift(drift, "reviews").actual());
        assertEquals(6,  datasetDrift(drift, "workplace").projected());
        assertEquals(1,  datasetDrift(drift, "workplace").actual());
        assertEquals(3,  datasetDrift(drift, "interview").projected());
        assertEquals(1,  datasetDrift(drift, "interview").actual());

        // Read-only: still wrong, because reporting is not repairing.
        assertEquals(8, projected(acme, "manager_count"), "reconcile must not have repaired anything");

        await(rebuilder.rebuild());
        assertTrue(await(rebuilder.reconcile()).isEmpty(), "an explicit rebuild must put it right");
    }

    /*
      Kind 2: an orphan row.

      The projection holds a company the source does not support. A write straight to
      company_stats_live does not fire the managers trigger, because nothing watches that table,
      so this is the shape of every corruption the trigger cannot undo.

      It is the kind that reaches a user: findCompanyListing joins company_stats_live on
      manager_count > 0, so the row puts a company with no managers on a public surface.
    */
    @Test
    void reconcileDetectsAnOrphanRow() throws Exception {
        long orphanCo = insertCompany("Orphaned Co");

        await(pool.preparedQuery("""
                INSERT INTO company_stats_live (company_id, manager_count, total_reviews, avg_rating)
                VALUES ($1, 4, 12, 4.5)
                """).execute(Tuple.of(orphanCo)));

        List<CompanyStatsRebuilder.Drift> drift = await(rebuilder.reconcile());
        assertEquals(2, drift.size(), () -> "both manager counts are unsupported: " + drift);
        assertTrue(drift.stream().allMatch(d -> d.companyId() == orphanCo));
        assertEquals(4, datasetDrift(drift, "managers").projected());
        assertEquals(0, datasetDrift(drift, "managers").actual());

        // Read-only, including for the case that is publicly visible. Tempting to auto-fix; no.
        assertEquals(4, projected(orphanCo, "manager_count"), "reconcile must not have repaired it");

        await(rebuilder.rebuild());
        assertEquals(0, projected(orphanCo, "manager_count"), "an explicit rebuild removes it");
        assertTrue(await(rebuilder.reconcile()).isEmpty());
    }

    /*
      Kind 3: a missing row.

      The reason reconcile() is driven from a union of projected and source company ids rather
      than a scan of the projection. A company that should have a row and has none is a company
      missing from the Companies tab.

      This is the case the previous implementation could not report at all: it iterated
      company_stats_live row by row, and the row it needed to examine is the one that does not
      exist. The bug was invisible because nothing ever called the method.
    */
    @Test
    void reconcileDetectsAMissingRow() throws Exception {
        long acme = insertCompany("Acme");
        insertManager("Ada Approved", acme, "approved", 3);
        insertCompanyReview(acme, 4.0);
        await(rebuilder.rebuild());   // a correct baseline, including the untriggered datasets
        assertEquals(1, projected(acme, "manager_count"), "precondition: the row exists");
        assertEquals(1, projected(acme, "workplace_count"));

        // Nothing watches company_stats_live, so no trigger restores this.
        await(pool.preparedQuery("DELETE FROM company_stats_live WHERE company_id = $1")
            .execute(Tuple.of(acme)));

        List<CompanyStatsRebuilder.Drift> drift = await(rebuilder.reconcile());
        assertEquals(3, drift.size(),
            () -> "managers, reviews and workplace all drift when the row vanishes: " + drift);
        assertTrue(drift.stream().allMatch(d -> d.companyId() == acme));
        assertEquals(0, datasetDrift(drift, "managers").projected(), "nothing projected");
        assertEquals(1, datasetDrift(drift, "managers").actual(),    "one manager in the source");
        assertEquals(0, datasetDrift(drift, "workplace").projected());
        assertEquals(1, datasetDrift(drift, "workplace").actual());

        // Read-only.
        assertEquals(0, projected(acme, "manager_count"), "reconcile must not have restored it");

        await(rebuilder.rebuild());
        assertTrue(await(rebuilder.reconcile()).isEmpty(), "an explicit rebuild restores it");
        assertEquals(1, projected(acme, "manager_count"));
    }

    @Test
    void reconcileIsQuietWhenTheProjectionAgreesWithSource() throws Exception {
        // The case that runs every six hours in production. A check that cried wolf on a healthy
        // projection would be turned off within a week, which is the same as not having one.
        long acme = insertCompany("Acme");
        insertManager("Ada Approved", acme, "approved", 3);
        insertManager("Pat Pending",  acme, "pending_approval", 9);
        insertSeedManager("Sam Seeded", acme, 9);
        insertCompanyReview(acme, 4.0);
        insertDeletedCompanyReview(acme, 1.0);
        insertInterviewReview(acme, 5.0);
        long empty = insertCompany("No Managers Co");

        // Direct SQL inserts do not maintain the workplace and interview columns - those have no
        // trigger - so a rebuild is what brings every dataset in line with source. That a rebuild
        // produces a state reconcile then calls clean is the invariant tying the two together: if
        // they disagreed, one of them would be wrong about what the projection should contain.
        await(rebuilder.rebuild());

        List<CompanyStatsRebuilder.Drift> drift = await(rebuilder.reconcile());
        assertTrue(drift.isEmpty(), () -> "expected no drift, got " + drift);
        assertEquals(0, projected(empty, "manager_count"),
            "a company with no qualifying managers has no row, and that is not drift");
    }

    @Test
    void reconcileIsQuietOnAnEmptyDatabase() throws Exception {
        // A drift check that cannot tell "nothing to compare" from "everything disagrees" would
        // alert on every fresh environment.
        assertTrue(await(rebuilder.reconcile()).isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name, slug, status) VALUES ($1,$2,'approved') RETURNING id")
            .execute(Tuple.of(name, name.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static long insertManager(String name, long companyId, String approvalStatus,
                                      int reviewsCount) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name, company, company_id, title, slug, status,
                                     approval_status, reviews_count, overall_rating)
                VALUES ($1, 'Acme', $2, 'Manager', $3, 'active', $4, $5, 4.0)
                RETURNING id
                """)
            .execute(Tuple.of(name, companyId, slug(name), approvalStatus, reviewsCount))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** An approved manager that refreshCompanyStats excludes by its {@code seed_} external id. */
    private static long insertSeedManager(String name, long companyId, int reviewsCount) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers(name, company, company_id, title, slug, status,
                                     approval_status, reviews_count, overall_rating, external_id)
                VALUES ($1, 'Acme', $2, 'Manager', $3, 'active', 'approved', $4, 4.0, $5)
                RETURNING id
                """)
            .execute(Tuple.of(name, companyId, slug(name), reviewsCount, "seed_" + slug(name)))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static void insertCompanyReview(long companyId, double rating) throws Exception {
        insertCompanyReview(companyId, rating, false);
    }

    /** A withdrawn workplace rating, which the projection must not count. */
    private static void insertDeletedCompanyReview(long companyId, double rating) throws Exception {
        insertCompanyReview(companyId, rating, true);
    }

    private static void insertCompanyReview(long companyId, double rating, boolean deleted) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO company_reviews(
                    company_id, overall_rating, work_life_balance, compensation_benefits,
                    career_growth, job_security, workload_sustainability, senior_leadership,
                    company_communication, flexibility, inclusion_belonging, tools_resources,
                    worked_from, deleted_at)
                VALUES ($1, $2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, DATE '2024-01-01',
                        CASE WHEN $3 THEN now() ELSE NULL END)
                """).execute(Tuple.of(companyId, java.math.BigDecimal.valueOf(rating), deleted)));
    }

    private static void insertInterviewReview(long companyId, double rating) throws Exception {
        insertInterviewReview(companyId, rating, false);
    }

    /** A withdrawn interview experience, which the projection must not count. */
    private static void insertDeletedInterviewReview(long companyId, double rating) throws Exception {
        insertInterviewReview(companyId, rating, true);
    }

    private static void insertInterviewReview(long companyId, double rating, boolean deleted) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO interview_reviews(company_id, overall_rating, outcome, interview_year, deleted_at)
                VALUES ($1, $2, 'offer', 2024, CASE WHEN $3 THEN now() ELSE NULL END)
                """).execute(Tuple.of(companyId, java.math.BigDecimal.valueOf(rating), deleted)));
    }

    private static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    /** The projected value, or 0 when the company has no row at all. */
    private static long projected(long companyId, String column) throws Exception {
        return await(pool.preparedQuery(
                "SELECT " + column + " AS v FROM company_stats_live WHERE company_id = $1")
            .execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().hasNext() ? rs.iterator().next().getLong("v") : 0L));
    }

    /** The one reported disagreement for a dataset, failing clearly when it is absent. */
    private static CompanyStatsRebuilder.Drift datasetDrift(
            List<CompanyStatsRebuilder.Drift> drift, String dataset) {
        return drift.stream().filter(d -> d.dataset().equals(dataset)).findFirst()
            .orElseThrow(() -> new AssertionError("no '" + dataset + "' drift reported in " + drift));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }
}
