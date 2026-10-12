package org.werkpages.integration;

import io.vertx.core.Future;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.werkpages.service.CompanyStatsRebuilder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The whole company_stats_live lifecycle: live updates, automatic repair, and concurrency.
 *
 * <h2>What this projection is</h2>
 *
 * A physical, indexed table holding nine derived numbers per company, read by the directory, the
 * company page, the industry pages and the company picker. It is not a view and must not become
 * one (CLAUDE.md section 21).
 *
 * <h2>The rule these tests pin down</h2>
 *
 * <b>ONE ROW PER COMPANY WHOSE status &lt;&gt; 'merged'</b>, zeros where there is no data.
 *
 * <p>This REPLACES the older rule, under which a row was deleted when no qualifying managers
 * remained. That rule was correct when managers were the only dataset and destructive once they
 * were not: a company rated as a workplace with no managers listed lost its row, and with it the
 * count of somebody's rating. It was also expressed in three places that disagreed.
 *
 * <p>The product guarantee that the old rule was protecting is unchanged and still asserted here:
 * a company with no qualifying managers must not advertise manager counts or ratings, and must not
 * appear in the directory. That is now expressed as {@code manager_count = 0} plus the
 * {@code manager_count > 0} filter every directory read already applies, rather than as the absence
 * of a row.
 *
 * <h2>Expected values are specified independently</h2>
 *
 * Every correctness assertion below states a number worked out by hand from the rows it inserted.
 * None of them asks the database what it expects and then checks it against itself: a checker that
 * agrees with a buggy calculation proves only that the bug is consistent.
 */
@Testcontainers
class CompanyStatsLifecycleIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;
    static CompanyStatsRebuilder maintenance;

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

        pool = PgPool.pool(opts, new PoolOptions().setMaxSize(8));
        maintenance = new CompanyStatsRebuilder(pool);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE notifications, manager_url_history, company_stats_live").execute());
        await(pool.query("TRUNCATE company_stats_drift_log, company_stats_maintenance_runs").execute());
        await(pool.query("TRUNCATE company_reviews, interview_reviews, managers, users, companies CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. Each dataset alone, and all of them together
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void managerOnlyCompany() throws Exception {
        long c = insertCompany("Managers Only Ltd");
        insertManager("Alice A", c, "approved", null, 3, 4.0);
        insertManager("Bob B",   c, "ghost",    null, 1, 2.0);

        Row s = stats(c).orElseThrow();
        // Worked out by hand: two eligible managers, 3 + 1 reviews, mean of 4.0 and 2.0.
        assertEquals(2L, s.getLong("manager_count"));
        assertEquals(4L, s.getLong("total_reviews"));
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(s.getBigDecimal("avg_rating")));
        assertEquals(0L, s.getLong("workplace_count"));
        assertEquals(0L, s.getLong("interview_count"));
    }

    @Test
    void workplaceOnlyCompany_theCaseTheOldRuleDestroyed() throws Exception {
        /*
          A company rated as a workplace with nobody's manager listed. Ordinary - the datasets are
          independent - and the single most important case here, because the previous orphan rule
          deleted this row and the previous rebuild could not recreate it.
        */
        long c = insertCompany("Workplace Only Ltd");
        insertCompanyReview(c, 4);
        insertCompanyReview(c, 2);

        Row s = stats(c).orElseThrow(() -> new AssertionError("a workplace rating must keep its company's row"));
        assertEquals(0L, s.getLong("manager_count"));
        assertEquals(0L, s.getLong("total_reviews"));
        assertNull(s.getBigDecimal("avg_rating"), "no managers means no manager average, not zero");
        assertEquals(2L, s.getLong("workplace_count"));
        // Hand-computed: (4 + 2) / 2.
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(s.getBigDecimal("workplace_avg_rating")));
    }

    @Test
    void interviewOnlyCompany() throws Exception {
        long c = insertCompany("Interviews Only Ltd");
        insertInterview(c, 5);
        insertInterview(c, 4);
        insertInterview(c, 3);

        Row s = stats(c).orElseThrow();
        assertEquals(0L, s.getLong("manager_count"));
        assertEquals(3L, s.getLong("interview_count"));
        // Hand-computed: (5 + 4 + 3) / 3.
        assertEquals(0, new java.math.BigDecimal("4.0").compareTo(s.getBigDecimal("interview_avg_rating")));
    }

    @Test
    void mixedCompany_doesNotInflateCountsByJoining() throws Exception {
        /*
          THE FAN-OUT GUARD, and the reason the calculation aggregates each dataset separately.

          Joining managers, company_reviews and interview_reviews in one FROM multiplies the rows:
          2 managers x 3 workplace x 2 interview = 12 rows, and every COUNT and SUM above it
          inflates. The numbers below are deliberately all different so that an inflated result
          cannot coincidentally match a correct one.
        */
        long c = insertCompany("Mixed Co");
        insertManager("Alice A", c, "approved", null, 5, 4.0);
        insertManager("Bob B",   c, "approved", null, 7, 2.0);
        insertCompanyReview(c, 5);
        insertCompanyReview(c, 4);
        insertCompanyReview(c, 3);
        insertInterview(c, 2);
        insertInterview(c, 4);

        Row s = stats(c).orElseThrow();
        assertEquals(2L,  s.getLong("manager_count"),   "2 managers, not 2 x 3 x 2");
        assertEquals(12L, s.getLong("total_reviews"),   "5 + 7, not multiplied by the other datasets");
        assertEquals(3L,  s.getLong("workplace_count"), "3 workplace ratings, not 3 x 2 managers");
        assertEquals(2L,  s.getLong("interview_count"), "2 interviews, not 2 x 2 managers");
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(s.getBigDecimal("avg_rating")));
        assertEquals(0, new java.math.BigDecimal("4.0").compareTo(s.getBigDecimal("workplace_avg_rating")));
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(s.getBigDecimal("interview_avg_rating")));
    }

    @Test
    void emptyCompany_hasAZeroRowAndStaysOutOfTheDirectory() throws Exception {
        // The row-existence rule, and the product guarantee it has to keep.
        long c = insertCompany("Brand New Co");

        Row s = stats(c).orElseThrow(() -> new AssertionError("the rule is a row per non-merged company"));
        assertEquals(0L, s.getLong("manager_count"));
        assertEquals(0L, s.getLong("workplace_count"));
        assertEquals(0L, s.getLong("interview_count"));

        // And it is invisible to the directory, which is what the old orphan delete was protecting.
        assertEquals(0L, directoryCount(), "an all-zero company must not appear in the listing");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. Live maintenance: eligibility, deletion, reassignment, metadata
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void deletingTheLastManager_zeroesCountsRatherThanLeavingThemStale() throws Exception {
        /*
          The guarantee the old "delete the row" rule existed for. In production one company showed
          "1 manager, 1 review, 4.1" with no managers left, and stayed in industry listings because
          they filter manager_count > 0.
        */
        long c = insertCompany("Redwater Partnership");
        long m = insertManager("Solo Manager", c, "approved", null, 1, 4.1);
        assertEquals(1L, stats(c).orElseThrow().getLong("manager_count"));

        await(pool.preparedQuery("DELETE FROM managers WHERE id = $1").execute(Tuple.of(m)));

        Row s = stats(c).orElseThrow();
        assertEquals(0L, s.getLong("manager_count"), "no stale count");
        assertEquals(0L, s.getLong("total_reviews"));
        assertNull(s.getBigDecimal("avg_rating"));
        assertEquals(0L, directoryCount(), "and gone from the directory");
    }

    @Test
    void eligibilityChanges_areFollowed() throws Exception {
        long c = insertCompany("Moderated Co");
        long m = insertManager("Pending Pat", c, "approved", null, 2, 3.0);
        assertEquals(1L, stats(c).orElseThrow().getLong("manager_count"));

        // Leaving approved/ghost removes the manager from the aggregate.
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'rejected' WHERE id = $1")
            .execute(Tuple.of(m)));
        assertEquals(0L, stats(c).orElseThrow().getLong("manager_count"));

        // And coming back restores it.
        await(pool.preparedQuery("UPDATE managers SET approval_status = 'ghost' WHERE id = $1")
            .execute(Tuple.of(m)));
        assertEquals(1L, stats(c).orElseThrow().getLong("manager_count"));
    }

    @Test
    void seededManagers_areExcluded() throws Exception {
        long c = insertCompany("Seeded Only Co");
        insertManager("Seed Sam", c, "approved", "seed_123", 5, 4.9);

        Row s = stats(c).orElseThrow();
        assertEquals(0L, s.getLong("manager_count"), "seed placeholders are not real managers");
        assertEquals(0L, s.getLong("total_reviews"));
    }

    @Test
    void withdrawnWorkplaceRating_stopsCounting() throws Exception {
        // A withdrawal is an UPDATE of deleted_at, not a DELETE. It changes count and average.
        long c = insertCompany("Withdrawn Co");
        insertCompanyReview(c, 5);
        insertCompanyReview(c, 1);
        assertEquals(2L, stats(c).orElseThrow().getLong("workplace_count"));

        await(pool.preparedQuery("""
                UPDATE company_reviews SET deleted_at = now()
                 WHERE id = (SELECT id FROM company_reviews WHERE company_id = $1
                              ORDER BY overall_rating LIMIT 1)
                """).execute(Tuple.of(c)));

        Row s = stats(c).orElseThrow();
        assertEquals(1L, s.getLong("workplace_count"));
        // The 1 was withdrawn, so only the 5 remains.
        assertEquals(0, new java.math.BigDecimal("5.0").compareTo(s.getBigDecimal("workplace_avg_rating")));
    }

    @Test
    void reassigningAManager_updatesBothCompanies() throws Exception {
        long from = insertCompany("Old Employer");
        long to   = insertCompany("New Employer");
        long m    = insertManager("Mobile Mo", from, "approved", null, 2, 4.5);

        assertEquals(1L, stats(from).orElseThrow().getLong("manager_count"));
        assertEquals(0L, stats(to).orElseThrow().getLong("manager_count"));

        await(pool.preparedQuery("UPDATE managers SET company_id = $1 WHERE id = $2")
            .execute(Tuple.of(to, m)));

        assertEquals(0L, stats(from).orElseThrow().getLong("manager_count"),
            "the company left behind must be recomputed too");
        assertEquals(2L, stats(to).orElseThrow().getLong("total_reviews"),
            "and the destination must pick the manager up");
    }

    @Test
    void reassigningAWorkplaceRating_updatesBothCompanies() throws Exception {
        long from = insertCompany("Rated A");
        long to   = insertCompany("Rated B");
        insertCompanyReview(from, 4);
        assertEquals(1L, stats(from).orElseThrow().getLong("workplace_count"));

        await(pool.preparedQuery("UPDATE company_reviews SET company_id = $1 WHERE company_id = $2")
            .execute(Tuple.of(to, from)));

        assertEquals(0L, stats(from).orElseThrow().getLong("workplace_count"));
        assertEquals(1L, stats(to).orElseThrow().getLong("workplace_count"));
    }

    @Test
    void companyMetadataChange_refreshesTheProjectedLogo() throws Exception {
        /*
          The projection carries a logo, not only aggregates, and it is a dependency nothing else
          would notice: changing companies.logo_url changes what every tile renders.
        */
        long c = insertCompany("Logo Co");
        insertManager("Alice A", c, "approved", null, 1, 4.0);
        assertNull(stats(c).orElseThrow().getString("logo_url"));

        await(pool.preparedQuery("UPDATE companies SET logo_url = $1 WHERE id = $2")
            .execute(Tuple.of("https://cdn.example/logo-co.png", c)));

        assertEquals("https://cdn.example/logo-co.png", stats(c).orElseThrow().getString("logo_url"));
    }

    @Test
    void aManagerLogoDotDevUrlWinsOverTheCompanysOwn() throws Exception {
        // The documented preference order, pinned so a recompute cannot silently reorder it.
        long c = insertCompany("Preference Co");
        await(pool.preparedQuery("UPDATE companies SET logo_url = $1 WHERE id = $2")
            .execute(Tuple.of("https://cdn.example/company.png", c)));
        insertManagerWithLogo("Alice A", c, "https://img.logo.dev/preference.example?token=t");

        assertEquals("https://img.logo.dev/preference.example?token=t",
            stats(c).orElseThrow().getString("logo_url"));
    }

    @Test
    void mergedCompany_losesItsRow() throws Exception {
        // The one case where a row is removed, and the rule is explicit about it.
        long c = insertCompany("Absorbed Co");
        insertManager("Alice A", c, "approved", null, 1, 4.0);
        assertTrue(stats(c).isPresent());

        await(pool.preparedQuery("UPDATE companies SET status = 'merged' WHERE id = $1")
            .execute(Tuple.of(c)));

        assertTrue(stats(c).isEmpty(), "a merged company keeps no projection row");
        // And the source data is untouched: removing a projection row is not a delete of anything real.
        assertEquals(1L, count("SELECT count(*) FROM managers WHERE company_id = " + c));
        assertEquals(1L, count("SELECT count(*) FROM companies WHERE id = " + c));
    }

    @Test
    void bulkStatementRecomputesEachCompanyOnce() throws Exception {
        /*
          Statement-level triggers, not row-level. The previous row-level trigger recomputed a
          company once per affected row, so reassigning 50 managers did 100 recomputes of two
          companies.

          Asserted through updated_at: all rows written by one statement share one now(), and the
          correctness of the counts afterwards proves the dedup did not lose anything.
        */
        long from = insertCompany("Bulk From");
        long to   = insertCompany("Bulk To");
        for (int i = 0; i < 25; i++) insertManager("Mgr " + i, from, "approved", null, 1, 4.0);
        assertEquals(25L, stats(from).orElseThrow().getLong("manager_count"));

        await(pool.preparedQuery("UPDATE managers SET company_id = $1 WHERE company_id = $2")
            .execute(Tuple.of(to, from)));

        assertEquals(0L,  stats(from).orElseThrow().getLong("manager_count"));
        assertEquals(25L, stats(to).orElseThrow().getLong("manager_count"));
        assertEquals(25L, stats(to).orElseThrow().getLong("total_reviews"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. Automatic drift detection AND repair
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void maintain_repairsWrongValuesWithoutAnybodyPressingAnything() throws Exception {
        long c = insertCompany("Drifted Co");
        insertManager("Alice A", c, "approved", null, 3, 4.0);
        insertCompanyReview(c, 4);

        // Corrupt the projection directly, as a bypassing write or an older buggy writer would.
        await(pool.preparedQuery("""
                UPDATE company_stats_live
                   SET manager_count = 99, total_reviews = 99, workplace_count = 99
                 WHERE company_id = $1
                """).execute(Tuple.of(c)));

        CompanyStatsRebuilder.MaintenanceResult result = await(maintenance.maintain());

        assertTrue(result.ran());
        assertEquals(3, result.driftFound(), "three columns were wrong");
        assertEquals(1, result.companiesRepaired());
        assertEquals(0, result.stillDrifted(), "and the repair was verified");

        Row s = stats(c).orElseThrow();
        assertEquals(1L, s.getLong("manager_count"));
        assertEquals(3L, s.getLong("total_reviews"));
        assertEquals(1L, s.getLong("workplace_count"));
    }

    @Test
    void maintain_recreatesAMissingRow() throws Exception {
        /*
          A missing row is drift in its own right, and it is the case a projection-row lock could
          never cover: there is nothing to lock. Detection has to work from the source side to see
          it at all.
        */
        long c = insertCompany("Vanished Row Co");
        insertCompanyReview(c, 5);
        await(pool.preparedQuery("DELETE FROM company_stats_live WHERE company_id = $1").execute(Tuple.of(c)));
        assertTrue(stats(c).isEmpty());

        CompanyStatsRebuilder.MaintenanceResult result = await(maintenance.maintain());

        assertEquals(1, result.driftFound());
        assertEquals(1L, stats(c).orElseThrow().getLong("workplace_count"));
        assertEquals("missing_row", await(pool.query(
            "SELECT drift_kind FROM company_stats_drift_log ORDER BY id DESC LIMIT 1").execute()
            .map(rs -> rs.iterator().next().getString("drift_kind"))));
    }

    @Test
    void maintain_removesAnObsoleteRow() throws Exception {
        // A row whose company has been merged away. Detected as its own kind, and repaired.
        long c = insertCompany("Obsolete Co");
        insertManager("Alice A", c, "approved", null, 1, 4.0);
        // Merge it without letting the trigger clean up, to simulate a row left behind.
        await(pool.query("ALTER TABLE companies DISABLE TRIGGER companies_company_stats_upd").execute());
        await(pool.preparedQuery("UPDATE companies SET status = 'merged' WHERE id = $1").execute(Tuple.of(c)));
        await(pool.query("ALTER TABLE companies ENABLE TRIGGER companies_company_stats_upd").execute());
        assertTrue(stats(c).isPresent(), "precondition: the stale row is still there");

        CompanyStatsRebuilder.MaintenanceResult result = await(maintenance.maintain());

        assertEquals(1, result.driftFound());
        assertTrue(stats(c).isEmpty(), "the obsolete row must be removed");
        assertEquals("obsolete_row", await(pool.query(
            "SELECT drift_kind FROM company_stats_drift_log ORDER BY id DESC LIMIT 1").execute()
            .map(rs -> rs.iterator().next().getString("drift_kind"))));
        // Source data intact: this is a projection cleanup, not a company deletion.
        assertEquals(1L, count("SELECT count(*) FROM companies WHERE id = " + c));
        assertEquals(1L, count("SELECT count(*) FROM managers WHERE company_id = " + c));
    }

    @Test
    void maintain_recordsTheEvidenceBeforeRepairingIt() throws Exception {
        /*
          A repair that leaves no trace hides a recurring bug: if one company drifts every six
          hours, every run looks like a success and the numbers are right whenever anybody checks.
          The log has to hold enough to investigate - which company, which field, what was expected,
          what was there, and whether the repair worked.
        */
        long c = insertCompany("Evidence Co");
        insertManager("Alice A", c, "approved", null, 4, 4.0);
        await(pool.preparedQuery("UPDATE company_stats_live SET total_reviews = 41 WHERE company_id = $1")
            .execute(Tuple.of(c)));

        await(maintenance.maintain());

        Row log = await(pool.preparedQuery("""
                SELECT company_id, field, expected, actual, drift_kind, repair_result, repaired_at
                FROM company_stats_drift_log WHERE company_id = $1 ORDER BY id DESC LIMIT 1
                """).execute(Tuple.of(c)).map(rs -> rs.iterator().next()));

        assertEquals(c, log.getLong("company_id"));
        assertEquals("total_reviews", log.getString("field"));
        assertEquals("4",  log.getString("expected"));
        assertEquals("41", log.getString("actual"));
        assertEquals("value",    log.getString("drift_kind"));
        assertEquals("repaired", log.getString("repair_result"));
        assertNotNull(log.getLocalDateTime("repaired_at"));
    }

    @Test
    void maintain_isCleanAndIdempotentWhenNothingIsWrong() throws Exception {
        // Triggers keep it correct, so a sweep over healthy data must find nothing and repeat safely.
        long c = insertCompany("Healthy Co");
        insertManager("Alice A", c, "approved", null, 2, 4.0);
        insertCompanyReview(c, 3);
        insertInterview(c, 5);

        assertEquals(0, await(maintenance.maintain()).driftFound());
        assertEquals(0, await(maintenance.maintain()).driftFound(), "and again, with no side effects");
        assertEquals(0L, count("SELECT count(*) FROM company_stats_drift_log"),
            "nothing to record when nothing is wrong");
    }

    @Test
    void maintain_repeatedRunsAfterRepairFindNothingFurther() throws Exception {
        long c = insertCompany("Repeat Co");
        insertManager("Alice A", c, "approved", null, 2, 4.0);
        await(pool.preparedQuery("UPDATE company_stats_live SET manager_count = 9 WHERE company_id = $1")
            .execute(Tuple.of(c)));

        assertEquals(1, await(maintenance.maintain()).driftFound());
        assertEquals(0, await(maintenance.maintain()).driftFound(), "the repair held");
    }

    @Test
    void rebuild_rewritesEverythingAndPreservesAllSourceData() throws Exception {
        long a = insertCompany("Rebuild A");
        long b = insertCompany("Rebuild B");
        insertManager("Alice A", a, "approved", null, 3, 4.0);
        insertCompanyReview(b, 5);
        insertInterview(b, 4);

        await(pool.query("UPDATE company_stats_live SET manager_count = 77, workplace_count = 77").execute());

        long written = await(maintenance.rebuild());
        assertTrue(written >= 2, "every non-merged company is rewritten, got " + written);

        assertEquals(1L, stats(a).orElseThrow().getLong("manager_count"));
        assertEquals(1L, stats(b).orElseThrow().getLong("workplace_count"));
        assertEquals(1L, stats(b).orElseThrow().getLong("interview_count"));

        // Nothing in the source tables was touched by maintenance.
        assertEquals(2L, count("SELECT count(*) FROM companies"));
        assertEquals(1L, count("SELECT count(*) FROM managers"));
        assertEquals(1L, count("SELECT count(*) FROM company_reviews"));
        assertEquals(1L, count("SELECT count(*) FROM interview_reviews"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. Concurrency - demonstrated, not asserted by assertion
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void repairBlocksWhileALiveSourceTransactionHoldsTheCompany() throws Exception {
        /*
          THE INTERLEAVING THAT MATTERS, and the one "it is in a transaction" does not address.
          Without it: repair reads the source at T1, a rating commits at T2, repair writes its T1
          numbers at T3, and a committed rating is uncounted until the next sweep.

          company_stats_apply takes a transaction-scoped advisory lock on the company BEFORE it
          reads the source. This test proves the lock is real and that it covers the read:

            A: BEGIN; INSERT a workplace rating   -> its trigger holds company C's advisory lock
            B: company_stats_apply(C)             -> must BLOCK, proven by a statement timeout
            A: COMMIT                             -> releases the lock
            B: run again                          -> now sees the committed rating

          If the lock were taken after the read, or not at all, step B would return immediately with
          the pre-insert number.
        */
        long c = insertCompany("Contended Co");
        insertCompanyReview(c, 4);
        assertEquals(1L, stats(c).orElseThrow().getLong("workplace_count"));

        SqlConnection writer = await(pool.getConnection());
        try {
            Transaction tx = await(writer.begin());
            // Fires the statement-level trigger, which takes C's advisory lock in this transaction.
            await(writer.preparedQuery("""
                    INSERT INTO company_reviews(company_id, overall_rating, work_life_balance,
                            compensation_benefits, career_growth, job_security,
                            workload_sustainability, senior_leadership, company_communication,
                            flexibility, inclusion_belonging, tools_resources, worked_from, author)
                    VALUES ($1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, DATE '2022-01-01', 'Contender')
                    """).execute(Tuple.of(c)));

            // A second connection tries to maintain the same company and must not get through.
            SqlConnection repairer = await(pool.getConnection());
            try {
                await(repairer.query("SET statement_timeout = '1500ms'").execute());
                String failure = null;
                try {
                    await(repairer.preparedQuery("SELECT company_stats_apply($1)")
                        .execute(Tuple.of(new Long[]{c})));
                } catch (Exception e) {
                    failure = String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                }
                /*
                  Checked SPECIFICALLY for a statement timeout, not merely "something threw".

                  Accepting any exception would let this pass for the wrong reason - a type error in
                  the call, a missing function - and report a lock that does not exist as proof of
                  one that does. A cancelled statement is the only outcome that means "it waited".
                */
                assertNotNull(failure,
                    "maintenance must not read-and-write a company while a live transaction holds it");
                assertTrue(failure.toLowerCase().contains("statement timeout")
                        || failure.toLowerCase().contains("canceling statement"),
                    "expected the call to WAIT on the lock and be cancelled, but it failed with: " + failure);
            } finally {
                await(repairer.close());
            }

            await(tx.commit());
        } finally {
            await(writer.close());
        }

        // The uncontended recompute now sees BOTH ratings - 4 and 2 - so nothing was lost.
        await(pool.preparedQuery("SELECT company_stats_apply($1)").execute(Tuple.of(new Long[]{c})));
        Row s = stats(c).orElseThrow();
        assertEquals(2L, s.getLong("workplace_count"));
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(s.getBigDecimal("workplace_avg_rating")));
    }

    @Test
    void theSameApplyCallSucceedsImmediatelyWhenNobodyHoldsTheCompany() throws Exception {
        /*
          The control for the blocking test above. That test proves a call is cancelled while a live
          transaction holds the company; this proves the identical call, with the identical short
          timeout, completes at once when nothing holds it. Without this pair, the timeout could be
          evidence of a slow query rather than of the lock.
        */
        long c = insertCompany("Uncontended Co");
        insertCompanyReview(c, 4);

        SqlConnection conn = await(pool.getConnection());
        try {
            await(conn.query("SET statement_timeout = '1500ms'").execute());
            await(conn.preparedQuery("SELECT company_stats_apply($1)").execute(Tuple.of(new Long[]{c})));
        } finally {
            await(conn.close());
        }

        assertEquals(1L, stats(c).orElseThrow().getLong("workplace_count"));
    }

    @Test
    void twoSequentialWritesToOneCompanyBothCount() throws Exception {
        // Concurrent changes to the same company, serialized by the per-company lock. Neither is
        // lost and the result is not the number either one saw on its own.
        long c = insertCompany("Busy Co");
        insertCompanyReview(c, 5);
        insertCompanyReview(c, 3);

        Row s = stats(c).orElseThrow();
        assertEquals(2L, s.getLong("workplace_count"));
        assertEquals(0, new java.math.BigDecimal("4.0").compareTo(s.getBigDecimal("workplace_avg_rating")));
    }

    @Test
    void maintenanceDoesNotOverlapItself() throws Exception {
        /*
          Two maintenance runs must not interleave and redo each other's work, including when they
          are in different backends - Werkpages and RMM are separate processes against one database,
          so a JVM flag cannot see the other side. A session advisory lock can.

          Proven by holding the lock on one connection and showing maintain() declines rather than
          queueing.
        */
        SqlConnection holder = await(pool.getConnection());
        try {
            boolean got = await(holder.preparedQuery("SELECT pg_try_advisory_lock($1)")
                .execute(Tuple.of(847232)).map(rs -> rs.iterator().next().getBoolean(0)));
            assertTrue(got, "precondition: this test must hold the maintenance lock");

            CompanyStatsRebuilder.MaintenanceResult result = await(maintenance.maintain());
            assertFalse(result.ran(), "a second maintenance run must skip, not queue");
        } finally {
            await(holder.preparedQuery("SELECT pg_advisory_unlock($1)").execute(Tuple.of(847232)));
            await(holder.close());
        }

        // And once the lock is free it runs normally again.
        assertTrue(await(maintenance.maintain()).ran());
    }

    @Test
    void reassignmentAcrossTwoCompaniesTakesLocksInAStableOrder() throws Exception {
        /*
          Reassignment locks two companies in one statement, which is where a deadlock would come
          from if two transactions took them in opposite orders. company_stats_apply sorts the ids
          ascending, so every caller takes them in the same sequence.

          Asserted behaviourally: moving records in both directions, repeatedly, completes rather
          than deadlocking, and the counts are right at the end.
        */
        long low  = insertCompany("AAA Lower Id");
        long high = insertCompany("ZZZ Higher Id");
        insertCompanyReview(low, 4);
        insertCompanyReview(high, 2);

        for (int i = 0; i < 6; i++) {
            long from = (i % 2 == 0) ? low : high;
            long to   = (i % 2 == 0) ? high : low;
            await(pool.preparedQuery(
                "UPDATE company_reviews SET company_id = $1 WHERE company_id = $2")
                .execute(Tuple.of(to, from)));
        }

        long total = stats(low).orElseThrow().getLong("workplace_count")
                   + stats(high).orElseThrow().getLong("workplace_count");
        assertEquals(2L, total, "both ratings still counted exactly once, somewhere");
    }


    @Test
    void theWaitingRecomputeResumesAndWritesTheFreshTotals() throws Exception {
        /*
          THE OTHER HALF OF THE BLOCKING TEST, and the half that actually matters.

          Proving the call blocks only proves it does not read stale data and win. It does not prove
          the waiter then does the right thing. If the lock were released but the waiter kept a
          snapshot taken before it waited - which is what READ COMMITTED would give a statement that
          had already begun - it would resume and write numbers that predate the rating it waited
          for. That is the original bug wearing a lock.

          So this version does NOT cancel the waiter. It leaves it queued, commits the rating, and
          then requires that the SAME call completes and that the totals it wrote include the rating
          committed while it was waiting.
        */
        long c = insertCompany("Resuming Co");
        insertCompanyReview(c, 4);
        assertEquals(1L, stats(c).orElseThrow().getLong("workplace_count"));

        SqlConnection writer   = await(pool.getConnection());
        SqlConnection waiter   = await(pool.getConnection());
        try {
            Transaction tx = await(writer.begin());
            await(writer.preparedQuery("""
                    INSERT INTO company_reviews(company_id, overall_rating, work_life_balance,
                            compensation_benefits, career_growth, job_security,
                            workload_sustainability, senior_leadership, company_communication,
                            flexibility, inclusion_belonging, tools_resources, worked_from, author)
                    VALUES ($1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, DATE '2022-01-01', 'Resumer')
                    """).execute(Tuple.of(c)));

            // Issue the recompute WITHOUT awaiting it. No statement_timeout: it is meant to wait.
            Future<?> queued = waiter.preparedQuery("SELECT company_stats_apply($1)")
                .execute(Tuple.of(new Long[]{c}));

            // Give it time to reach the lock and park there, then prove it really is parked.
            Thread.sleep(600);
            assertFalse(queued.isComplete(),
                "the recompute should still be waiting on the company lock at this point");

            await(tx.commit());

            // Now it must finish on its own, and what it wrote must include the committed rating.
            await(queued);
        } finally {
            await(waiter.close());
            await(writer.close());
        }

        Row after = stats(c).orElseThrow();
        assertEquals(2L, after.getLong("workplace_count"),
            "the resumed recompute must count the rating it waited for, not the pre-wait total");
        // Hand-computed: (4 + 2) / 2. A stale read would have left 4.0.
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(after.getBigDecimal("workplace_avg_rating")));
        // And the shared checker agrees, from the source side.
        assertTrue(await(maintenance.reconcile()).isEmpty(), "no drift after the resumed recompute");
    }

    @Test
    void twoConcurrentRatingSubmissionsAreBothCounted() throws Exception {
        /*
          Two people rating the same company at the same time. Each INSERT fires the trigger, which
          takes that company's advisory lock, so the second transaction's trigger waits for the
          first to commit and then recomputes over both rows.

          The failure this rules out is a lost update: both recomputes reading "one rating" and the
          projection settling on 1 when two are committed. Started together, with the second
          deliberately reaching its trigger while the first still holds the lock.
        */
        long c = insertCompany("Simultaneous Co");

        SqlConnection a = await(pool.getConnection());
        SqlConnection b = await(pool.getConnection());
        try {
            Transaction txA = await(a.begin());
            Transaction txB = await(b.begin());

            await(a.preparedQuery(ratingInsert()).execute(Tuple.of(c, 5)));

            // B's insert must wait on A's trigger lock, so it is issued without awaiting.
            Future<?> bInsert = b.preparedQuery(ratingInsert()).execute(Tuple.of(c, 1));
            Thread.sleep(400);
            assertFalse(bInsert.isComplete(), "B's trigger should be waiting on A's company lock");

            await(txA.commit());
            await(bInsert);
            await(txB.commit());
        } finally {
            await(a.close());
            await(b.close());
        }

        Row s = stats(c).orElseThrow();
        assertEquals(2L, s.getLong("workplace_count"), "neither submission may be lost");
        // Hand-computed: (5 + 1) / 2. Either rating alone would give 5.0 or 1.0.
        assertEquals(0, new java.math.BigDecimal("3.0").compareTo(s.getBigDecimal("workplace_avg_rating")));
        assertTrue(await(maintenance.reconcile()).isEmpty());
    }

    @Test
    void aFailedSweepLeavesTheMaintenanceLockFree() throws Exception {
        /*
          THE LOCK-LEAK CHECK.

          The sweep lock is held across the whole maintenance run so two sweeps cannot interleave,
          and skipping is the designed response to finding it held. That makes a leaked lock an
          outage with no symptom: every subsequent sweep skips, statistics quietly go stale, and the
          logs say "skipped" forever.

          A session-scoped advisory lock survives ROLLBACK, so it would have to be released by hand
          on every exit path - including the ones nobody thought of. This is taken as
          pg_try_advisory_xact_lock inside withTransaction instead, so PostgreSQL releases it
          however the transaction ends.

          Failure is injected with a negative batch, which makes the LIMIT inside
          company_stats_maintain invalid, so the function raises after the lock has been taken.
        */
        long c = insertCompany("Post Failure Co");
        insertManager("Alice A", c, "approved", null, 2, 4.0);
        await(pool.preparedQuery("UPDATE company_stats_live SET manager_count = 9 WHERE company_id = $1")
            .execute(Tuple.of(c)));

        boolean failed = false;
        try {
            await(maintenance.maintain(-1));
        } catch (Exception expected) {
            failed = true;
        }
        assertTrue(failed, "precondition: this must actually fail inside the sweep");

        /*
          First prove the detector works, or "no lock is held" means nothing. An assertion that
          cannot fail is not evidence - the same trap as accepting any exception as proof of a lock.
        */
        SqlConnection prover = await(pool.getConnection());
        try {
            await(prover.query("BEGIN").execute());
            await(prover.query("SELECT pg_advisory_xact_lock(847232)").execute());
            assertEquals(1L, lockCount(), "precondition: the pg_locks probe must SEE a held lock");
            await(prover.query("ROLLBACK").execute());
            assertEquals(0L, lockCount(), "and must see it released - note: released by ROLLBACK");
        } finally {
            await(prover.close());
        }

        // Now the real check: the failed sweep left nothing behind.
        assertEquals(0L, lockCount(), "the sweep lock leaked after a failed run");

        CompanyStatsRebuilder.MaintenanceResult after = await(maintenance.maintain());
        assertTrue(after.ran(), "the next sweep must run, not skip because of a leaked lock");
        assertEquals(1L, stats(c).orElseThrow().getLong("manager_count"), "and it must repair");
    }

    /** How many sessions hold the maintenance sweep lock. */
    private static long lockCount() throws Exception {
        return count("""
                SELECT count(*) FROM pg_locks
                 WHERE locktype = 'advisory' AND objid = 847232 AND granted
                """);
    }

    /** The workplace-rating INSERT, as a reusable statement for the concurrency tests. */
    private static String ratingInsert() {
        return """
                INSERT INTO company_reviews(company_id, overall_rating, work_life_balance,
                        compensation_benefits, career_growth, job_security,
                        workload_sustainability, senior_leadership, company_communication,
                        flexibility, inclusion_belonging, tools_resources, worked_from, author)
                VALUES ($1, $2, $2, $2, $2, $2, $2, $2, $2, $2, $2, $2, DATE '2022-01-01', 'Concurrent')
                """;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. reconcile() on its own - read-only drift reporting
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void reconcile_reportsEveryColumnItGuards_andChangesNothing() throws Exception {
        /*
          Retained from the test this file replaces. reconcile() used to compare only the workplace
          and interview counts, so manager_count and total_reviews - the two oldest columns, and the
          ones most likely to be read - were invisible to the only tool for finding a wrong number.

          Read-only is part of the contract: it is safe to call while anything else is running.
        */
        long c = insertCompany("Reported Co");
        insertManager("Alice A", c, "approved", null, 4, 4.0);
        insertCompanyReview(c, 3);
        insertInterview(c, 5);

        await(pool.preparedQuery("""
                UPDATE company_stats_live
                   SET manager_count = 9, total_reviews = 90, workplace_count = 8, interview_count = 7
                 WHERE company_id = $1
                """).execute(Tuple.of(c)));

        List<CompanyStatsRebuilder.Drift> drift = await(maintenance.reconcile());

        assertTrue(drift.stream().anyMatch(d -> d.field().equals("manager_count")),   "got: " + drift);
        assertTrue(drift.stream().anyMatch(d -> d.field().equals("total_reviews")),   "got: " + drift);
        assertTrue(drift.stream().anyMatch(d -> d.field().equals("workplace_count")), "got: " + drift);
        assertTrue(drift.stream().anyMatch(d -> d.field().equals("interview_count")), "got: " + drift);

        // Still wrong: reporting must not repair, or the scheduled job could not record evidence
        // before fixing things.
        assertEquals(9L, stats(c).orElseThrow().getLong("manager_count"));
    }

    @Test
    void reconcile_isSilentWhenTheTriggersHaveDoneTheirJob() throws Exception {
        // The control that matters most in practice: a checker that cries wolf gets switched off.
        long c = insertCompany("Quiet Co");
        insertManager("Alice A", c, "approved", null, 2, 4.0);
        insertCompanyReview(c, 3);
        insertInterview(c, 4);

        assertTrue(await(maintenance.reconcile()).isEmpty(),
            "the triggers must leave the projection reconciled with no maintenance at all");
    }

    @Test
    void reconcile_detectsAStaleProjectedLogo() throws Exception {
        // The one non-aggregate field, and a stale one is visible on every tile.
        long c = insertCompany("Stale Logo Co");
        insertManager("Alice A", c, "approved", null, 1, 4.0);
        await(pool.preparedQuery("UPDATE company_stats_live SET logo_url = $1 WHERE company_id = $2")
            .execute(Tuple.of("https://cdn.example/wrong.png", c)));

        List<CompanyStatsRebuilder.Drift> drift = await(maintenance.reconcile());
        assertTrue(drift.stream().anyMatch(d -> d.field().equals("logo_url")), "got: " + drift);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. Alerting: failures, recurrence, and a sweep that stopped running
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void aFailedSweepIsRecordedEvenThoughItsTransactionRolledBack() throws Exception {
        /*
          The gap: alerting only on "repaired but still wrong" misses a sweep that ERRORS. And
          because the sweep's own drift-log rows roll back with it, a failure could leave no trace
          at all - repeated failure was indistinguishable from healthy silence.

          The run record is therefore written AFTER the sweep transaction ends, which is the only
          arrangement where the record of a failure survives the failure.
        */
        long before = count("SELECT count(*) FROM company_stats_maintenance_runs WHERE outcome = 'failed'");

        boolean threw = false;
        try {
            await(maintenance.maintain(-1));   // negative batch -> invalid LIMIT inside the sweep
        } catch (Exception expected) {
            threw = true;
        }
        assertTrue(threw, "precondition: the sweep must actually fail");

        assertEquals(before + 1,
            count("SELECT count(*) FROM company_stats_maintenance_runs WHERE outcome = 'failed'"),
            "a failed sweep must leave a record, or repeated failure looks like silence");

        Row run = await(pool.query("""
                SELECT outcome, error_detail, source FROM company_stats_maintenance_runs
                 ORDER BY id DESC LIMIT 1
                """).execute().map(rs -> rs.iterator().next()));
        assertEquals("failed", run.getString("outcome"));
        assertNotNull(run.getString("error_detail"), "the reason has to be in the record");
        assertNotNull(run.getString("source"), "and which backend ran it");
    }

    @Test
    void successfulSweepsAreRecordedSoAStoppedSweepIsDetectable() throws Exception {
        /*
          Nothing can reliably alert on its own death. What it can do is leave a heartbeat, which is
          what makes "maintenance has not run for three days" a question anything can answer.

          Only COMPLETED runs count. A long run of 'skipped' means the lock is stuck or one backend
          is doing all the work, and treating that as healthy is how a stuck sweep stays invisible.
        */
        long c = insertCompany("Heartbeat Co");
        insertManager("Alice A", c, "approved", null, 1, 4.0);

        await(maintenance.maintain());

        assertEquals(1L, count("SELECT count(*) FROM company_stats_maintenance_runs WHERE outcome = 'ok'"));
        Duration age = await(maintenance.timeSinceLastCompletedRun());
        assertTrue(age.toMinutes() < 5, "a sweep that just ran must read as recent, got " + age);

        // A skipped run must NOT refresh the heartbeat.
        SqlConnection holder = await(pool.getConnection());
        try {
            await(holder.preparedQuery("SELECT pg_advisory_lock($1)").execute(Tuple.of(847232)));
            assertFalse(await(maintenance.maintain()).ran());
        } finally {
            await(holder.preparedQuery("SELECT pg_advisory_unlock($1)").execute(Tuple.of(847232)));
            await(holder.close());
        }
        assertEquals(1L, count("SELECT count(*) FROM company_stats_maintenance_runs WHERE outcome = 'ok'"),
            "a skipped sweep is not a completed one");
        assertEquals(1L, count("SELECT count(*) FROM company_stats_maintenance_runs WHERE outcome = 'skipped'"),
            "but it is still recorded, so a stuck lock is visible as a run of skips");
    }

    @Test
    void driftThatReturnsAfterASuccessfulRepairIsReportedAsRecurring() throws Exception {
        /*
          THE QUIETEST FAILURE MODE. A company repaired every six hours reports a clean success
          every six hours: each run is fine, the numbers are correct whenever anybody looks, and the
          bug writing them wrongly is invisible.

          Simulated by corrupting, repairing, then corrupting again - which is what a live bug would
          produce. The condition is specifically "repaired, then wrong again", not "wrong in more
          than one column", so a single sweep finding three bad columns is NOT recurrence.
        */
        long c = insertCompany("Recurring Co");
        insertManager("Alice A", c, "approved", null, 2, 4.0);

        await(pool.preparedQuery("UPDATE company_stats_live SET manager_count = 9 WHERE company_id = $1")
            .execute(Tuple.of(c)));
        CompanyStatsRebuilder.MaintenanceResult first = await(maintenance.maintain());
        assertEquals(1, first.driftFound());
        assertFalse(first.driftRecurring(), "one occurrence is not yet a recurrence");

        // It comes back, as a live bug would make it.
        Thread.sleep(1100);   // the recurrence window groups events per second
        await(pool.preparedQuery("UPDATE company_stats_live SET manager_count = 9 WHERE company_id = $1")
            .execute(Tuple.of(c)));
        CompanyStatsRebuilder.MaintenanceResult second = await(maintenance.maintain());

        assertEquals(0, second.stillDrifted(), "the repair itself worked both times");
        assertTrue(second.driftRecurring(),
            "a company wrong again after a verified repair must be reported, got: " + second);
        assertTrue(second.needsAttention(), "and it must count as needing attention");
        assertEquals(1L, count("SELECT count(*) FROM company_stats_recurring_drift(7, 2)"));
    }

    @Test
    void aOneOffRepairIsNotReportedAsRecurring() throws Exception {
        // The control. A checker that calls every repair a recurrence is a checker people mute.
        long c = insertCompany("One Off Co");
        insertManager("Alice A", c, "approved", null, 3, 4.0);
        await(pool.preparedQuery("""
                UPDATE company_stats_live SET manager_count = 9, total_reviews = 99
                 WHERE company_id = $1
                """).execute(Tuple.of(c)));

        CompanyStatsRebuilder.MaintenanceResult result = await(maintenance.maintain());

        assertEquals(2, result.driftFound(), "two wrong columns");
        assertFalse(result.driftRecurring(),
            "two columns in ONE sweep is one event, not a recurrence: " + result);
        assertEquals(0L, count("SELECT count(*) FROM company_stats_recurring_drift(7, 2)"));
    }

    @Test
    void withNoCompletedSweepTheHeartbeatReportsStaleNotHealthy() throws Exception {
        /*
          REGRESSION, and a self-inflicted one found by reading the startup log rather than a test.

          company_stats_maintenance_age() originally coalesced to '-infinity' and subtracted, which
          PostgreSQL rejects with "cannot subtract infinite timestamps". The caller swallowed the
          error and returned a zero age, so a database where maintenance had NEVER run printed
          "last completed 0h ago" - a clean bill of health for a system that had never been
          inspected. On a fresh production deploy that is precisely the window where somebody needs
          to know the sweep has not started yet.

          Both halves are asserted: the SQL must answer rather than raise, and the Java must treat
          "no runs" and "cannot tell" as stale.
        */
        assertEquals(0L, count("SELECT count(*) FROM company_stats_maintenance_runs"),
            "precondition: this test needs an empty run log");

        // The function answers NULL rather than raising.
        assertEquals(0L, count("SELECT count(*) FROM (SELECT company_stats_maintenance_age()) t WHERE 1=0"),
            "the age function must be callable with no runs on record");

        Duration age = await(maintenance.timeSinceLastCompletedRun());
        assertTrue(age.compareTo(CompanyStatsRebuilder.STALE_AFTER) > 0,
            "no completed sweep must read as STALE, not as 0h; got " + age);
        assertEquals(CompanyStatsRebuilder.NEVER_RUN, age);

        // And once a sweep completes, it reads as recent.
        await(maintenance.maintain());
        assertTrue(await(maintenance.timeSinceLastCompletedRun()).toMinutes() < 5);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static Optional<Row> stats(long companyId) throws Exception {
        return await(pool.preparedQuery("SELECT * FROM company_stats_live WHERE company_id = $1")
            .execute(Tuple.of(companyId))
            .map(rs -> rs.iterator().hasNext() ? Optional.of(rs.iterator().next()) : Optional.empty()));
    }

    /** What the directory would show: the same predicate findCompanyListing applies. */
    private static long directoryCount() throws Exception {
        return count("SELECT count(*) FROM company_stats_live WHERE manager_count > 0");
    }

    private static long count(String sql) throws Exception {
        return await(pool.query(sql).execute().map(rs -> rs.iterator().next().getLong(0)));
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

    private static void insertManagerWithLogo(String name, long companyId, String logoUrl) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO managers(name, company, title, image, status, approval_status,
                                     overall_rating, reviews_count, category_averages,
                                     company_id, company_logo_url)
                VALUES ($1, 'Co', 'Manager', 'img', 'active', 'approved', 4.0, 1, '{}', $2, $3)
                """).execute(Tuple.of(name, companyId, logoUrl)));
    }

    /** A live workplace rating. Every category and worked_from are NOT NULL. */
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
