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

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The V69 and V70 backfills, run as statements against rows that look like production's.
 *
 * <p>Flyway has already applied both to an empty database by the time these run, so the migration's
 * own SQL is re-executed here against seeded rows. That is the point: the interesting behaviour is
 * what it does to existing data, and an empty run proves nothing.
 *
 * <p>Re-running is also exactly what the guard clause has to survive, so this doubles as the
 * idempotence test.
 */
@Testcontainers
class DeclaredCountryBackfillIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("werkpages_test")
        .withUsername("test")
        .withPassword("test");

    static Pool pool;

    /** The statements V69 and V70 apply, kept in step with the migrations. */
    private static final String BACKFILL_MANAGERS = """
        UPDATE managers SET declared_country = country, declared_precision = 'country'
         WHERE country IS NOT NULL AND btrim(country) <> '' AND declared_precision IS NULL
        """;
    private static final String PROMOTE_STATE = """
        UPDATE managers SET declared_state = state, declared_precision = 'state'
         WHERE state IS NOT NULL AND btrim(state) <> '' AND declared_precision = 'country'
           AND declared_state IS NULL
        """;
    private static final String BACKFILL_REVIEWS = """
        UPDATE reviews r SET declared_country = m.country, declared_precision = 'country'
          FROM managers m
         WHERE r.manager_id = m.id AND m.country IS NOT NULL AND btrim(m.country) <> ''
           AND r.declared_precision IS NULL
        """;

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
    }

    @BeforeEach
    void cleanDb() throws Exception {
        await(pool.query("TRUNCATE managers, companies, users, reviews CASCADE").execute());
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        pool.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void aManagersPublicCountryBecomesItsDeclaredCountry() throws Exception {
        long id = insertManager("Pat Legacy", "LegacyCo", "Canada", "Ontario", "Toronto");

        await(pool.query(BACKFILL_MANAGERS).execute());

        Row m = manager(id);
        assertEquals("Canada",  m.getString("declared_country"));
        assertEquals("country", m.getString("declared_precision"));
    }

    @Test
    void theCountryBackfillAlonePromotesNothingElse() throws Exception {
        /*
          V69 moves country and stops. State waits for V70, which ships alongside the edit fields
          that make it correctable; city never moves at all, because /find writes the *searcher's*
          city onto the manager they searched for.
        */
        long id = insertManager("Sam Inferred", "InferCo", "Canada", "Ontario", "Toronto");

        await(pool.query(BACKFILL_MANAGERS).execute());

        Row m = manager(id);
        assertNull(m.getString("declared_state"), "state is V70's job, not V69's");
        assertNull(m.getString("declared_city"),  "city is the searcher's, not the manager's");
        assertEquals("Ontario", m.getString("state"), "the legacy columns themselves are untouched");
        assertEquals("Toronto", m.getString("city"));
    }

    @Test
    void aManagerWithNoCountryIsLeftAlone() throws Exception {
        long id = insertManager("No Country", "NowhereCo", null, null, null);

        await(pool.query(BACKFILL_MANAGERS).execute());

        assertNull(manager(id).getString("declared_precision"));
    }

    @Test
    void opinionsInheritTheirManagersCountry() throws Exception {
        long managerId = insertManager("Rated Legacy", "RatedCo", "Canada", null, null);
        insertReview(managerId);

        await(pool.query(BACKFILL_REVIEWS).execute());

        Row r = review(managerId);
        assertEquals("Canada",  r.getString("declared_country"));
        assertEquals("country", r.getString("declared_precision"));
    }

    @Test
    void anAnswerSomebodyActuallyGaveIsNeverOverwritten() throws Exception {
        /*
          The guard that makes this safe to re-run, and safe to ship after the new forms are live.
          Somebody who has said "Kitchener" must not be quietly demoted to "Canada" because a
          backfill ran afterwards.
        */
        long managerId = insertManager("Already Declared", "DeclCo", "Canada", null, null);
        await(pool.preparedQuery("""
                UPDATE managers SET declared_country = 'Canada', declared_state = 'Ontario',
                       declared_city = 'Kitchener', declared_precision = 'city' WHERE id = $1
                """).execute(Tuple.of(managerId)));

        await(pool.query(BACKFILL_MANAGERS).execute());

        Row m = manager(managerId);
        assertEquals("city",      m.getString("declared_precision"), "precision must not be downgraded");
        assertEquals("Kitchener", m.getString("declared_city"));
    }

    @Test
    void runningItTwiceChangesNothingTheSecondTime() throws Exception {
        long id = insertManager("Twice Run", "TwiceCo", "Canada", null, null);

        await(pool.query(BACKFILL_MANAGERS).execute());
        Row first = manager(id);
        await(pool.query(BACKFILL_MANAGERS).execute());
        Row second = manager(id);

        assertEquals(first.getString("declared_country"),   second.getString("declared_country"));
        assertEquals(first.getString("declared_precision"), second.getString("declared_precision"));
    }


    // ── V70: state, once it is correctable ────────────────────────────────────

    @Test
    void stateIsPromotedOnceTheEditFlowCanCorrectIt() throws Exception {
        long id = insertManager("Pat Province", "ProvCo", "Canada", "Ontario", "Toronto");

        await(pool.query(BACKFILL_MANAGERS).execute());
        await(pool.query(PROMOTE_STATE).execute());

        Row m = manager(id);
        assertEquals("Canada",  m.getString("declared_country"));
        assertEquals("Ontario", m.getString("declared_state"));
        assertEquals("state",   m.getString("declared_precision"));
        assertNull(m.getString("declared_city"), "city is still never promoted - /find contaminates it");
    }

    @Test
    void anAnswerMoreSpecificThanStateIsNotDowngraded() throws Exception {
        // Somebody who said Kitchener must not be walked back to Ontario by a later migration.
        long id = insertManager("Said Kitchener", "KitCo", "Canada", "Ontario", "Toronto");
        await(pool.preparedQuery("""
                UPDATE managers SET declared_country = 'Canada', declared_state = 'Ontario',
                       declared_city = 'Kitchener', declared_precision = 'city' WHERE id = $1
                """).execute(Tuple.of(id)));

        await(pool.query(PROMOTE_STATE).execute());

        Row m = manager(id);
        assertEquals("city",      m.getString("declared_precision"));
        assertEquals("Kitchener", m.getString("declared_city"));
    }

    @Test
    void aManagerWithNoStateStaysAtCountry() throws Exception {
        long id = insertManager("Country Only", "CountryCo", "Canada", null, null);

        await(pool.query(BACKFILL_MANAGERS).execute());
        await(pool.query(PROMOTE_STATE).execute());

        assertEquals("country", manager(id).getString("declared_precision"));
    }

    @Test
    void theEditRequestCanCarryAWholeLocation() throws Exception {
        // The point of V70: every rung of the location is correctable, not just country.
        Row cols = await(pool.query("""
                SELECT count(*) FILTER (WHERE column_name = 'new_declared_state')      AS st,
                       count(*) FILTER (WHERE column_name = 'new_declared_city')       AS ci,
                       count(*) FILTER (WHERE column_name = 'new_declared_precision')  AS pr,
                       count(*) FILTER (WHERE column_name = 'new_company_location_id') AS loc,
                       count(*) FILTER (WHERE column_name = 'new_country')             AS co
                FROM information_schema.columns WHERE table_name = 'manager_edits'
                """).execute().map(rs -> rs.iterator().next()));
        assertEquals(1L, cols.getLong("co"),  "country was already editable");
        assertEquals(1L, cols.getLong("st"));
        assertEquals(1L, cols.getLong("ci"));
        assertEquals(1L, cols.getLong("pr"));
        assertEquals(1L, cols.getLong("loc"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Row manager(long id) throws Exception {
        return await(pool.preparedQuery("""
                SELECT country, state, city, declared_country, declared_state, declared_city,
                       declared_precision
                FROM managers WHERE id = $1
                """).execute(Tuple.of(id)).map(rs -> rs.iterator().next()));
    }

    private static Row review(long managerId) throws Exception {
        return await(pool.preparedQuery(
                "SELECT declared_country, declared_precision FROM reviews WHERE manager_id = $1")
            .execute(Tuple.of(managerId)).map(rs -> rs.iterator().next()));
    }

    private static long insertManager(String name, String company, String country,
                                      String state, String city) throws Exception {
        return await(pool.preparedQuery("""
                INSERT INTO managers (name, company, title, status, approval_status,
                                      country, state, city, slug, overall_rating, reviews_count,
                                      category_averages, created_at, updated_at)
                VALUES ($1,$2,'Manager','active','approved',$3,$4,$5,$6,0,0,'{}'::jsonb,now(),now())
                RETURNING id
                """)
            .execute(Tuple.of(name, company, country, state, city,
                              name.toLowerCase().replace(" ", "-")))
            .map(rs -> rs.iterator().next().getLong("id")));
    }

    private static void insertReview(long managerId) throws Exception {
        await(pool.preparedQuery("""
                INSERT INTO reviews (manager_id, author, overall_rating, manager_company,
                                     manager_title, verified, helpful_count, created_at, updated_at)
                VALUES ($1, 'AnonLegacy', 4.0, 'RatedCo', 'Manager', true, 0, now(), now())
                """).execute(Tuple.of(managerId)));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
