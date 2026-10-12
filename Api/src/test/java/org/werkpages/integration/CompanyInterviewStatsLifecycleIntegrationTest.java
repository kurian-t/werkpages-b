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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The interview-statistics projection: drift is found, recorded, repaired and verified.
 *
 * <h2>What was missing</h2>
 *
 * <p>{@code company_interview_stats} had triggers from V48, so it satisfied the first three rules
 * of CLAUDE.md section 21 - authoritative sources, same-transaction maintenance, one component
 * owning it. It had neither of the last two: no full rebuild, and nothing that ever compared the
 * projection against the reviews it summarises.
 *
 * <p>Section 21 states the consequence directly: a read table without those is "not a cache; it is
 * a second source of truth that will silently diverge". This one is read by
 * {@code getCompanyInterviews} and rendered on the company page, so divergence means wrong numbers
 * on a page people read, with nothing anywhere reporting it.
 *
 * <h2>And one race it actually had</h2>
 *
 * <p>V48's trigger recomputed from the source with no lock, so two transactions inserting reviews
 * for the same company each counted a snapshot excluding the other's uncommitted row, and the
 * second commit wrote a count permanently one short. {@link #twoConcurrentReviewsForOneCompanyAreBothCounted}
 * is that race, and it is the test that fails against V48 as written.
 */
@Testcontainers
class CompanyInterviewStatsLifecycleIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("iv_stats_test")
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

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()), new PoolOptions().setMaxSize(6));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query(
            "TRUNCATE interview_reviews, company_interview_stats, company_stats_drift_log, "
          + "company_stats_live, managers, companies, users CASCADE").execute());
    }

    // ── the projection is maintained at all ───────────────────────────────────

    /** The baseline: the trigger projects what the reviews say, so drift means something. */
    @Test
    void theProjectionFollowsTheReviews() throws Exception {
        long company = insertCompany("Interviewed Inc");
        insertReview(company, 4.0, "offer",    3, 2021);
        insertReview(company, 2.0, "no_offer", 1, 2022);

        Row stats = statsFor(company);
        assertNotNull(stats, "two live reviews must produce a row");
        assertEquals(2, stats.getInteger("review_count"));
        assertEquals(1, stats.getInteger("offer_count"));
        assertEquals(1, stats.getInteger("no_offer_count"));
        assertEquals(0, drift().size(), "a freshly maintained projection has no drift");
    }

    /**
     * Soft-deleting the last review removes the row rather than leaving zeroes.
     *
     * <p>The existence rule, which the drift check depends on: it has to be able to tell "should
     * have no row" apart from "should have a row saying zero", or every company that ever had a
     * review would look permanently drifted.
     */
    @Test
    void softDeletingTheLastReviewRemovesTheRow() throws Exception {
        long company = insertCompany("Gone Quiet Ltd");
        UUID review = insertReview(company, 4.0, "offer", 2, 2023);

        await(pool.preparedQuery("UPDATE interview_reviews SET deleted_at = now() WHERE id = $1")
            .execute(Tuple.of(review)));

        assertNull(statsFor(company), "no live reviews means no row");
        assertEquals(0, drift().size());
    }

    // ── detection ─────────────────────────────────────────────────────────────

    /**
     * A wrong number is detected, with both sides reported.
     *
     * <p>Written directly into the projection to simulate what an unlocked trigger, a crashed
     * process or a future caller that forgets to maintain it would leave behind. There is no way to
     * provoke real drift on demand once the triggers are correct, which is precisely why the
     * checker has to exist: the causes are the ones nobody predicted.
     */
    @Test
    void aWrongCountIsDetected() throws Exception {
        long company = insertCompany("Drifted Corp");
        insertReview(company, 4.0, "offer", 2, 2023);
        insertReview(company, 3.0, "offer", 2, 2024);

        await(pool.preparedQuery(
                "UPDATE company_interview_stats SET review_count = 7 WHERE company_id = $1")
            .execute(Tuple.of(company)));

        var rows = drift();
        assertEquals(1, rows.size());
        assertEquals("review_count", rows.get(0).getString("field"));
        assertEquals("2", rows.get(0).getString("expected"));
        assertEquals("7", rows.get(0).getString("actual"));
        assertEquals("value", rows.get(0).getString("drift_kind"));
    }

    /** A projection row that should exist and does not. */
    @Test
    void aMissingRowIsDetected() throws Exception {
        long company = insertCompany("Vanished Row Co");
        insertReview(company, 4.0, "offer", 2, 2023);

        await(pool.preparedQuery("DELETE FROM company_interview_stats WHERE company_id = $1")
            .execute(Tuple.of(company)));

        var rows = drift();
        assertEquals(1, rows.size());
        assertEquals("missing_row", rows.get(0).getString("drift_kind"));
    }

    /**
     * A projection row for a company with nothing to project.
     *
     * <p>The reviews are deleted outright, behind the trigger, so the row is left stranded. A
     * stranded row is not harmless: the company page would show interview statistics for a company
     * whose interview reviews no longer exist.
     */
    @Test
    void anObsoleteRowIsDetected() throws Exception {
        long company = insertCompany("Stranded Row Co");
        insertReview(company, 4.0, "offer", 2, 2023);

        await(pool.query("ALTER TABLE interview_reviews DISABLE TRIGGER interview_reviews_stats").execute());
        await(pool.preparedQuery("DELETE FROM interview_reviews WHERE company_id = $1")
            .execute(Tuple.of(company)));
        await(pool.query("ALTER TABLE interview_reviews ENABLE TRIGGER interview_reviews_stats").execute());

        var rows = drift();
        assertEquals(1, rows.size());
        assertEquals("obsolete_row", rows.get(0).getString("drift_kind"));
    }

    // ── repair, through the one scheduled entry point ─────────────────────────

    /**
     * The scheduled job repairs interview drift, logs it, and verifies the repair took.
     *
     * <p>Called through {@code company_stats_maintain}, not the interview-specific function,
     * because that is what the scheduler and the admin action actually call. A repair routine the
     * scheduled job does not reach is indistinguishable from no repair routine.
     */
    @Test
    void theScheduledMaintenanceRepairsAndRecordsInterviewDrift() throws Exception {
        long company = insertCompany("Repair Me Inc");
        insertReview(company, 4.0, "offer",    2, 2023);
        insertReview(company, 2.0, "no_offer", 3, 2024);

        await(pool.preparedQuery(
                "UPDATE company_interview_stats SET review_count = 99, offer_count = 0 WHERE company_id = $1")
            .execute(Tuple.of(company)));

        Row result = await(pool.query(
            "SELECT * FROM company_stats_maintain(200)").execute()).iterator().next();

        assertTrue(result.getInteger("drift_found") >= 2, "both wrong fields should be found");
        assertEquals(1, result.getInteger("companies_repaired"));
        assertEquals(0, result.getInteger("still_drifted"), "the repair has to hold");

        Row fixed = statsFor(company);
        assertEquals(2, fixed.getInteger("review_count"));
        assertEquals(1, fixed.getInteger("offer_count"));

        // The evidence, attributed to the right projection.
        var log = await(pool.preparedQuery(
                "SELECT field, repair_result, projection FROM company_stats_drift_log "
              + "WHERE company_id = $1 ORDER BY field").execute(Tuple.of(company)));
        assertTrue(log.size() >= 2);
        for (Row entry : log) {
            assertEquals("company_interview_stats", entry.getString("projection"));
            assertEquals("repaired", entry.getString("repair_result"),
                "a repair that was verified must say so");
        }
        assertEquals(0, drift().size());
    }

    /**
     * One maintenance call covers both projections, and reports both.
     *
     * <p>The reason the interview half was folded into {@code company_stats_maintain} rather than
     * given a parallel entry point. The caller's question is "are the statistics right", and a
     * number answering it for one projection while another quietly disagreed is how a projection
     * goes unwatched for a year.
     */
    @Test
    void oneMaintenanceCallCoversBothProjections() throws Exception {
        long company = insertCompany("Both Projections Co");
        insertManager(company, "Both Projections Co");
        insertReview(company, 4.0, "offer", 2, 2023);

        // Break one field in each projection.
        await(pool.preparedQuery(
                "UPDATE company_interview_stats SET review_count = 42 WHERE company_id = $1")
            .execute(Tuple.of(company)));
        await(pool.preparedQuery(
                "UPDATE company_stats_live SET manager_count = 42 WHERE company_id = $1")
            .execute(Tuple.of(company)));

        Row result = await(pool.query(
            "SELECT * FROM company_stats_maintain(200)").execute()).iterator().next();
        assertTrue(result.getInteger("drift_found") >= 2,
            "the total has to include findings from both projections");
        assertEquals(0, result.getInteger("still_drifted"));

        assertEquals(1, statsFor(company).getInteger("review_count"));
        Row live = await(pool.preparedQuery("SELECT * FROM company_stats_live WHERE company_id = $1")
            .execute(Tuple.of(company))).iterator().next();
        assertEquals(1L, live.getLong("manager_count"));

        // Both projections named in the log, so a reader can tell which drifted.
        var projections = await(pool.query(
            "SELECT DISTINCT projection FROM company_stats_drift_log ORDER BY projection").execute());
        assertEquals(2, projections.size());
    }

    // ── the rebuild utility (section 21, rule 4) ──────────────────────────────

    /**
     * The whole projection can be discarded and rebuilt from the reviews alone.
     *
     * <p>Rule 4. Without it, a projection that has gone wrong in a way the incremental path cannot
     * express has no way back, and the only remedy is hand-written SQL during an incident.
     */
    @Test
    void theProjectionRebuildsFromNothing() throws Exception {
        long a = insertCompany("Rebuild A");
        long b = insertCompany("Rebuild B");
        insertReview(a, 4.0, "offer",    2, 2022);
        insertReview(a, 3.0, "no_offer", 4, 2023);
        insertReview(b, 5.0, "offer",    1, 2024);

        await(pool.query("TRUNCATE company_interview_stats").execute());
        assertEquals(2, drift().size(), "two companies should be reported as missing rows");

        long written = await(pool.query("SELECT company_interview_stats_apply(NULL) AS n").execute())
            .iterator().next().getLong("n");
        assertTrue(written >= 2);

        assertEquals(2, statsFor(a).getInteger("review_count"));
        assertEquals(1, statsFor(b).getInteger("review_count"));
        assertEquals(0, drift().size(), "a rebuild must leave no drift behind");
    }

    /** A rebuild also removes rows that should not exist, not only writes the ones that should. */
    @Test
    void theRebuildRemovesStrandedRows() throws Exception {
        long company = insertCompany("Rebuild Removes Co");
        await(pool.preparedQuery(
                "INSERT INTO company_interview_stats(company_id, review_count) VALUES ($1, 5)")
            .execute(Tuple.of(company)));

        await(pool.query("SELECT company_interview_stats_apply(NULL)").execute());

        assertNull(statsFor(company), "a row with no reviews behind it must go");
        assertEquals(0, drift().size());
    }

    /**
     * The application's own rebuild covers both projections, not just the first.
     *
     * <p>{@code CompanyStatsRebuilder.rebuild()} is what the admin recovery action calls. It
     * recomputed {@code company_stats_live} alone, so pressing it on a company page showing wrong
     * interview figures would have reported success and changed nothing about them - the worst
     * shape of failure for a recovery tool, because it also removes the suspicion that something
     * is still wrong.
     */
    @Test
    void theApplicationRebuildCoversBothProjections() throws Exception {
        long company = insertCompany("Admin Rebuild Co");
        insertManager(company, "Admin Rebuild Co");
        insertReview(company, 4.0, "offer", 2, 2023);

        await(pool.query("TRUNCATE company_interview_stats").execute());
        await(pool.query("TRUNCATE company_stats_live").execute());

        long written = await(new org.werkpages.service.CompanyStatsRebuilder(pool).rebuild());
        assertTrue(written > 0);

        assertEquals(1, statsFor(company).getInteger("review_count"),
            "the interview projection has to come back too");
        assertTrue(await(pool.preparedQuery("SELECT 1 FROM company_stats_live WHERE company_id = $1")
            .execute(Tuple.of(company))).iterator().hasNext());
        assertEquals(0, drift().size());
    }

    // ── the race ──────────────────────────────────────────────────────────────

    /**
     * Two reviews inserted concurrently for one company are both counted.
     *
     * <p>This is the regression test for V48's unlocked trigger. Both transactions begin, each
     * inserts a review, and only then do they commit - so under READ COMMITTED each trigger's
     * recount saw its own row and not the other's, and whichever committed second wrote a count of
     * one. The projection then said one review forever, because nothing ever looked again.
     *
     * <p>With the advisory lock taken before the source read, the second transaction blocks until
     * the first commits and then counts both. The two interview years differ because of the unique
     * index that allows one review per person per company per year.
     */
    @Test
    void twoConcurrentReviewsForOneCompanyAreBothCounted() throws Exception {
        long company = insertCompany("Concurrent Co");
        UUID userA = insertUser("auth0|iv-conc-a", "ivconca");
        UUID userB = insertUser("auth0|iv-conc-b", "ivconcb");

        SqlConnection c1 = await(pool.getConnection());
        SqlConnection c2 = await(pool.getConnection());
        try {
            Transaction t1 = await(c1.begin());
            Transaction t2 = await(c2.begin());

            // First transaction inserts and its trigger runs, taking the per-company lock.
            await(insertReviewOn(c1, company, userA, 4.0, "offer", 2, 2023));

            /*
              The second insert cannot be awaited before the first commits: its trigger blocks
              while the first transaction holds the lock, which is the mechanism under test.
              Awaiting it here would hang the test rather than fail it.
            */
            Future<?> second = insertReviewOn(c2, company, userB, 2.0, "no_offer", 3, 2024);

            /*
              And this barrier is why the test is worth anything.

              Starting the second insert does not mean the server has begun it. Without waiting for
              it to actually block, the commit below can win the race and the second trigger then
              reads a snapshot that already contains the first review - so the count is right, and
              the test passes whether the lock exists or not. The first version of this test did
              exactly that, and proved nothing.

              Waiting until a backend is genuinely blocked on an ungranted lock pins the ordering:
              the second trigger has started, and cannot have read a snapshot containing the first
              review, because that review is still uncommitted.
            */
            awaitBlockedBackend();

            await(t1.commit());
            await(second);
            await(t2.commit());
        } finally {
            await(c1.close());
            await(c2.close());
        }

        Row stats = statsFor(company);
        assertEquals(2, stats.getInteger("review_count"),
            "both concurrent reviews must be counted; one means the recount raced");
        assertEquals(1, stats.getInteger("offer_count"));
        assertEquals(1, stats.getInteger("no_offer_count"));
        assertEquals(0, drift().size());
    }

    // ── merged companies ──────────────────────────────────────────────────────

    /**
     * A merged company keeps no interview statistics row.
     *
     * <p>Same rule {@code company_stats_live} follows. A merged company is not a company any more;
     * leaving its statistics behind means a page that should have redirected shows numbers.
     */
    @Test
    void aMergedCompanyLosesItsRow() throws Exception {
        long company = insertCompany("Merged Away Co");
        insertReview(company, 4.0, "offer", 2, 2023);
        assertNotNull(statsFor(company));

        await(pool.preparedQuery("UPDATE companies SET status = 'merged' WHERE id = $1")
            .execute(Tuple.of(company)));
        await(pool.preparedQuery("SELECT company_interview_stats_apply(ARRAY[$1]::BIGINT[])")
            .execute(Tuple.of(company)));

        assertNull(statsFor(company));
        assertEquals(0, drift().size());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long insertCompany(String name) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name, status) VALUES ($1, 'approved') RETURNING id")
            .execute(Tuple.of(name))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private void insertManager(long companyId, String companyName) throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO managers(name, company, title, status, approval_status, company_id, reviews_count) "
              + "VALUES ($1, $2, 'Manager', 'active', 'approved', $3, 0)")
            .execute(Tuple.of("Stats Manager", companyName, companyId)));
    }

    private UUID insertUser(String auth0Id, String username) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO users(auth0_id, email, username, first_name, last_name, role) "
              + "VALUES ($1,$2,$3,'Test','User','user') RETURNING id")
            .execute(Tuple.of(auth0Id, username + "@test.com", username))
            .map(rs -> rs.iterator().next().getUUID("id")));
    }

    private UUID insertReview(long companyId, double rating, String outcome, int rounds, int year)
            throws Exception {
        UUID user = insertUser("auth0|iv-" + UUID.randomUUID(), "iv" + Math.abs(UUID.randomUUID().hashCode()));
        return await(insertReviewOn(pool, companyId, user, rating, outcome, rounds, year));
    }

    private static Future<UUID> insertReviewOn(io.vertx.sqlclient.SqlClient client, long companyId,
                                               UUID userId, double rating, String outcome,
                                               int rounds, int year) {
        return client.preparedQuery(
                "INSERT INTO interview_reviews(company_id, user_id, overall_rating, difficulty, "
              + "outcome, rounds, interview_year) VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING id")
            .execute(Tuple.of(companyId, userId, rating, rounds, outcome, rounds, year))
            .map(rs -> rs.iterator().next().getUUID("id"));
    }

    private Row statsFor(long companyId) throws Exception {
        var rows = await(pool.preparedQuery("SELECT * FROM company_interview_stats WHERE company_id = $1")
            .execute(Tuple.of(companyId)));
        return rows.iterator().hasNext() ? rows.iterator().next() : null;
    }

    private java.util.List<Row> drift() throws Exception {
        var rows = await(pool.query("SELECT * FROM company_interview_stats_detect_drift(1000)").execute());
        var out = new java.util.ArrayList<Row>();
        rows.forEach(out::add);
        return out;
    }

    /**
     * Blocks until some backend is waiting on a lock it has not been granted.
     *
     * <p>Polled on a separate pooled connection, so it cannot be the thing that is blocked. Fails
     * the test rather than returning if nothing blocks within the timeout: "nothing ever blocked"
     * means the interleaving under test did not happen, and a silent return there would make this
     * a test that passes by accident.
     */
    private void awaitBlockedBackend() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            long waiting = await(pool.query("SELECT count(*) AS n FROM pg_locks WHERE NOT granted")
                .execute()).iterator().next().getLong("n");
            if (waiting > 0) return;
            Thread.sleep(25);
        }
        fail("no backend ever blocked, so the concurrent interleaving under test did not occur");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
