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
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V108 against a database that already holds the production defect.
 *
 * <p><b>Why this test exists and the other V108 test cannot replace it.</b>
 * {@code ReviewIntegrationTest.seedWrittenWithoutItsOriginIsClassifiedByTheDatabase} proves the
 * trigger fires, which is V108's PREVENTION. It cannot prove the REPAIR, because every integration
 * database in this suite is migrated to the head before a test writes anything, so the backfill
 * runs against an empty table and corrects nothing. The only way to exercise the repair is to stop
 * at V107, write the defective rows, and then let V108 run - which is exactly what production did
 * on 2026-11-15 and what it will do when V108 deploys.
 *
 * <p><b>The fixture is the production shape, reconstructed from evidence rather than invented.</b>
 * Three weighted, authorless ratings still carrying {@code origin = 'contributor'}. They cannot
 * have existed before 2026-11-15, because the column they carry was added by V106 and production
 * applied V106 that day; and V106's own backfill - whose predicate selects exactly this shape -
 * ran in the same transaction. So they were written afterwards, by a deployed build whose
 * {@code createSeedReview} does not name {@code origin}. That build is a real thing: neither
 * product's generator named the column until 2026-11-14, and nothing guarantees both backends were
 * rebuilt from those commits.
 *
 * <p>Crucially the fixture also sets the manager cache and the company projection to the WRONG
 * values - the ones a build with pre-V106 arithmetic writes, counting the placeholder as genuine -
 * because a repair that only fixes the column while leaving the figures describing a row that no
 * longer counts is not a repair. That was the whole complaint: not a misleading dashboard number
 * but a manager's public average computed as the mean of a genuine rating and a fabricated one.
 *
 * <p>{@code @TestInstance(PER_CLASS)} and the ordering are deliberate: the migration runs ONCE, in
 * {@code setUpAll} after the fixture is written, and every test then reads the one outcome. Each
 * assertion is a separate question about the same migration run, which is how production will see
 * it.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V108PlaceholderClassificationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("v108_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;

    /** The three managers, mirroring the three rows found in production. */
    static long managerOnlyPlaceholder;
    static long managerPlaceholderAndGenuine;
    static long managerSecondCompany;
    static long companyA;
    static long companyB;

    @BeforeAll
    void setUpAll() throws Exception {
        // ── 1. stop at V107, which is what production was holding ────────────
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .target(org.flywaydb.core.api.MigrationVersion.fromVersion("107"))
            .load()
            .migrate();

        pool = PgPool.pool(new PgConnectOptions()
            .setHost(postgres.getHost())
            .setPort(postgres.getMappedPort(5432))
            .setDatabase(postgres.getDatabaseName())
            .setUser(postgres.getUsername())
            .setPassword(postgres.getPassword()), new PoolOptions().setMaxSize(5));

        assertEquals("107", headVersion(), "the fixture must be written against V107, not the head");

        // The trigger V108 installs does not exist yet, so a plain INSERT takes the column default
        // and produces the defective shape exactly as the stale deployment does.
        companyA = insertCompany("Defect Industries", "defect-industries");
        companyB = insertCompany("Second Defect Co", "second-defect-co");

        managerOnlyPlaceholder       = insertManager("Dale Adams",    "Defect Industries", companyA);
        managerPlaceholderAndGenuine = insertManager("Kendra Levine", "Defect Industries", companyA);
        managerSecondCompany         = insertManager("Jas Nuthall",   "Second Defect Co",  companyB);

        insertMisclassifiedPlaceholder(managerOnlyPlaceholder,       "Defect Industries", 4.0);
        insertMisclassifiedPlaceholder(managerPlaceholderAndGenuine, "Defect Industries", 2.0);
        insertMisclassifiedPlaceholder(managerSecondCompany,         "Second Defect Co",  5.0);

        // One real rating, so this manager's average is provably the mean of a genuine rating and
        // a fabricated one before the migration, and provably the genuine one alone after it.
        insertGenuineRating(managerPlaceholderAndGenuine, "Defect Industries", 4.0);

        // The caches a pre-V106 build writes: the placeholder counted.
        setCache(managerOnlyPlaceholder,       1, new BigDecimal("4.0"));
        setCache(managerPlaceholderAndGenuine, 2, new BigDecimal("3.0"));
        setCache(managerSecondCompany,         1, new BigDecimal("5.0"));

        // And the company projection built from those wrong caches.
        await(pool.query("SELECT refresh_company_stats(id) FROM companies").execute());

        assertEquals(3L, countUnclassified(), "precondition: three defective rows");

        // ── 2. now let V108 run, as production will ──────────────────────────
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migrations")
            .load()
            .migrate();

        /*
          V108 APPLIED, which is not the same question as V108 being the newest.

          This asserted that 108 was the HEAD, which is a proxy that fails the moment any migration
          is added after it - V109 did exactly that - while saying nothing more about whether V108
          itself ran. The message was always about application, so the assertion now is too.
        */
        assertTrue(appliedVersions().contains("108"), "V108 must have applied");
    }

    /** Every migration version recorded as successfully applied. */
    private java.util.List<String> appliedVersions() throws Exception {
        return await(pool.query("""
                SELECT version FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                """).execute().map(rs -> {
                    java.util.List<String> out = new java.util.ArrayList<>();
                    rs.forEach(r -> out.add(r.getString("version")));
                    return out;
                }));
    }

    @AfterAll
    void tearDownAll() throws Exception {
        if (pool != null) pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    // ── the repair ───────────────────────────────────────────────────────────

    /**
     * Asserted over the three managers by id, not as a count of the whole table.
     *
     * <p>A global count made this test depend on which others had run: the prevention test writes
     * a fourth placeholder onto a new manager, and the class shares one database and one migration
     * run by design. Naming the three rows the migration was accountable for is also the stronger
     * claim, since the complaint was about three specific profiles.
     */
    @Test
    void everyMisclassifiedPlaceholderIsNowAnExample() throws Exception {
        assertEquals(0L, countUnclassified(),
            "all three, not one: the correction is set-based and names no id");
        for (long id : new long[]{managerOnlyPlaceholder, managerPlaceholderAndGenuine,
                                  managerSecondCompany}) {
            assertEquals("platform_example", await(pool.preparedQuery(
                    "SELECT origin FROM reviews WHERE manager_id = $1 AND weight").execute(
                        Tuple.of(id))
                .map(rs -> rs.iterator().next().getString("origin"))),
                "manager " + id + "'s placeholder must be reclassified");
        }
    }

    /** The rows are recoverable, which is what makes the rollback script real rather than a plan. */
    @Test
    void theOriginalRowsAreBackedUpBeforeBeingChanged() throws Exception {
        Long backed = await(pool.query(
                "SELECT COUNT(*) AS c FROM reviews_seed_reclassify_backup "
              + "WHERE origin = 'contributor'").execute()
            .map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(3L, backed, "the backup holds what each row used to be, not what it became");
    }

    /**
     * A manager holding only a demonstration has no rating. A genuine zero, not a missing one.
     */
    @Test
    void aManagerHoldingOnlyAPlaceholderIsRecomputedToNoRatingAtAll() throws Exception {
        Row cached = cacheOf(managerOnlyPlaceholder);
        assertEquals(0, cached.getInteger("reviews_count"));
        assertNull(cached.getBigDecimal("overall_rating"),
            "and no average: the only rating it had was a demonstration");
    }

    /**
     * The defect's real consequence, undone: a public average that was the mean of a genuine
     * rating and a fabricated one becomes the genuine rating alone.
     */
    @Test
    void aManagerWithBothKeepsOnlyTheGenuineRating() throws Exception {
        Row cached = cacheOf(managerPlaceholderAndGenuine);
        assertEquals(1, cached.getInteger("reviews_count"),
            "one genuine rating, where the cache said two");
        assertEquals(0, new BigDecimal("4.0").compareTo(cached.getBigDecimal("overall_rating")),
            "4.0, the real rating - not 3.0, the mean of it and a 2.0 nobody wrote");
    }

    /**
     * Company statistics follow, for every affected company rather than the first one.
     *
     * <p>They are not computed from {@code reviews} at all: {@code company_stats_live} aggregates
     * the MANAGER caches, so the correctness of this depends entirely on step 4 having recomputed
     * every one of them. Two companies in the fixture precisely because a loop that corrects only
     * the first would pass with one.
     */
    @Test
    void companyStatisticsAreRecomputedForEveryAffectedCompany() throws Exception {
        Row a = companyStats(companyA);
        assertEquals(1, a.getInteger("total_reviews"),
            "two managers at this company, one genuine rating between them");
        assertEquals(0, new BigDecimal("4.0").compareTo(a.getBigDecimal("avg_rating")));

        Row b = companyStats(companyB);
        assertEquals(0, b.getInteger("total_reviews"),
            "the second company's only rating was a demonstration");
        assertNull(b.getBigDecimal("avg_rating"));
    }

    /**
     * And the figures agree with the source data, which is the check V108 makes of itself.
     *
     * <p>Asserted here as well as in the migration because a {@code RAISE NOTICE} inside a
     * migration is read by whoever is watching the deploy, and nobody is watching a test.
     */
    @Test
    void thereIsNoDriftLeftBetweenTheProjectionAndItsSource() throws Exception {
        Long drifted = await(pool.query("""
                SELECT COUNT(*) AS c
                  FROM company_stats_live s
                  JOIN (
                      SELECT c.id,
                             COALESCE(SUM(m.reviews_count), 0)::INTEGER AS total,
                             ROUND(AVG(m.overall_rating) FILTER (
                                 WHERE m.overall_rating IS NOT NULL AND m.reviews_count > 0
                             )::NUMERIC, 1) AS avg
                        FROM companies c
                        LEFT JOIN managers m ON m.company_id = c.id
                         AND m.approval_status IN ('approved', 'ghost')
                       GROUP BY c.id
                  ) expected ON expected.id = s.company_id
                 WHERE s.total_reviews <> expected.total
                    OR s.avg_rating IS DISTINCT FROM expected.avg
                """).execute().map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(0L, drifted, "every company projection matches what the managers now say");
    }

    /**
     * The admin counter, which is what made the defect visible in the first place.
     *
     * <p>This is {@code ManagerService.getStats}'s weighted-opinions query verbatim. It was
     * climbing as ghosts were created, and nobody could make it move from the admin panel, because
     * nothing in the panel writes a weighted row. After V108 no weighted row is a contributor's,
     * so it is 0 and stays 0.
     */
    @Test
    void theWeightedOpinionsCounterReadsZero() throws Exception {
        Long weighted = await(pool.query(
                "SELECT COUNT(*) AS c FROM reviews WHERE origin = 'contributor' AND weight = TRUE "
              + "AND (weight_expires_on IS NULL OR weight_expires_on > CURRENT_DATE)").execute()
            .map(rs -> rs.iterator().next().getLong("c")));
        assertEquals(0L, weighted);
    }

    /**
     * Prevention, on the same database, so repair and prevention are proved against one install.
     *
     * <p>A FRESH manager, because {@code idx_one_seed_per_manager} is unique on
     * {@code (manager_id) WHERE weight AND deleted_at IS NULL}: a manager already carrying a
     * placeholder cannot take a second, and writing this against one of the three above failed on
     * that index rather than on anything to do with classification.
     */
    @Test
    void aFurtherSeedWrittenByAStaleBuildIsClassifiedOnTheWayIn() throws Exception {
        long fresh = insertManager("Newly Ghosted", "Second Defect Co", companyB);
        insertMisclassifiedPlaceholder(fresh, "Second Defect Co", 3.0);

        assertEquals(0L, countUnclassified(),
            "the trigger classifies it, so a stale deployment cannot recreate the defect");
        assertEquals("platform_example", await(pool.preparedQuery(
                "SELECT origin FROM reviews WHERE manager_id = $1").execute(Tuple.of(fresh))
            .map(rs -> rs.iterator().next().getString("origin"))));

        // And it is not arithmetic on the way in either: a brand-new ghost has no rating.
        Row cached = cacheOf(fresh);
        assertEquals(0, cached.getInteger("reviews_count"));
        assertNull(cached.getBigDecimal("overall_rating"));
    }

    // ── fixture helpers ──────────────────────────────────────────────────────

    private Long countUnclassified() throws Exception {
        return await(pool.query(
                "SELECT COUNT(*) AS c FROM reviews "
              + "WHERE origin = 'contributor' AND weight = TRUE AND user_id IS NULL").execute()
            .map(rs -> rs.iterator().next().getLong("c")));
    }

    private String headVersion() throws Exception {
        return await(pool.query("""
                SELECT version FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank DESC LIMIT 1
                """).execute().map(rs -> rs.iterator().next().getString("version")));
    }

    private Row cacheOf(long managerId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT reviews_count, overall_rating FROM managers WHERE id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next()));
    }

    private Row companyStats(long companyId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT total_reviews, avg_rating FROM company_stats_live WHERE company_id = $1")
            .execute(Tuple.of(companyId)).map(rs -> rs.iterator().next()));
    }

    private void setCache(long managerId, int count, BigDecimal rating) throws Exception {
        await(pool.preparedQuery(
                "UPDATE managers SET reviews_count = $2, overall_rating = $3 WHERE id = $1")
            .execute(Tuple.of(managerId, count, rating)).mapEmpty());
    }

    private long insertCompany(String name, String slug) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO companies(name, status, slug) VALUES ($1,'ghost',$2) RETURNING id")
            .execute(Tuple.of(name, slug)).map(rs -> rs.iterator().next().getLong("id")));
    }

    private long insertManager(String name, String company, long companyId) throws Exception {
        return await(pool.preparedQuery(
                "INSERT INTO managers(name, company, company_id, title, image, status, "
              + "approval_status, overall_rating, reviews_count, category_averages, slug) "
              + "VALUES ($1,$2,$3,'VP','img','active','ghost',NULL,0,'{}',$4) RETURNING id")
            .execute(Tuple.of(name, company, companyId,
                              name.toLowerCase().replaceAll("[^a-z0-9]+", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    /** The defective shape: weighted, authorless, and left to the column default. */
    private void insertMisclassifiedPlaceholder(long managerId, String company, double rating)
            throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, user_id, author, overall_rating, manager_company, "
              + "manager_title, worked_from, weight, created_at) "
              + "VALUES ($1, NULL, 'SwiftFinch38', $2, $3, 'VP', CURRENT_DATE - 60, TRUE, now())")
            .execute(Tuple.of(managerId, rating, company)).mapEmpty());
    }

    private void insertGenuineRating(long managerId, String company, double rating)
            throws Exception {
        await(pool.preparedQuery(
                "INSERT INTO reviews(manager_id, author, overall_rating, manager_company, "
              + "manager_title, worked_from, weight, created_at) "
              + "VALUES ($1,'Real Person',$2,$3,'VP',CURRENT_DATE - 10, FALSE, now())")
            .execute(Tuple.of(managerId, rating, company)).mapEmpty());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
